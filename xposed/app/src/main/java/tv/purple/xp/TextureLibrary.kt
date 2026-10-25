package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pictures for the Overlay filter.
 *
 * Built-in textures ship inside the module as Java resources under "textures/" (copied in at build
 * time from the repo's textures folder, with an index of file names and an optional manifest of
 * friendly names and suggested blend settings). The module's own class loader can read them
 * whether the module runs under LSPosed or is embedded by LSPatch.
 *
 * The viewer's own pictures are copied into Twitch's private files folder when picked, so they
 * keep working after the picker's temporary permission ends.
 */
object TextureLibrary {

    class Tex(val file: String, val name: String, val category: String, val blend: Int,
              val opacity: Int, val tile: Boolean)

    /** Overlay source: "tex:<file>", "img:<absolute path>" or "color:<argb int>". */
    const val KEY_SRC = "src"

    private val cache = ConcurrentHashMap<String, Bitmap>()
    @Volatile private var list: List<Tex>? = null

    fun builtIns(): List<Tex> {
        list?.let { return it }
        val cl = TextureLibrary::class.java.classLoader
        val files = runCatching {
            cl?.getResourceAsStream("textures/index.txt")?.bufferedReader()?.readLines()
        }.getOrNull().orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
        val meta = HashMap<String, Tex>()
        runCatching {
            val txt = cl?.getResourceAsStream("textures/manifest.json")?.bufferedReader()?.readText() ?: ""
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val f = o.optString("file")
                meta[f] = Tex(f, o.optString("name", pretty(f)), o.optString("category", "Other"),
                    FilterDefs.BLEND_MODES.indexOf(o.optString("blend", "Overlay")).let { if (it < 0) 12 else it },
                    o.optInt("opacity", 60), o.optBoolean("tile", true))
            }
        }
        val out = files.filter { it.substringAfterLast('.').lowercase() in setOf("jpg", "jpeg", "png", "webp") }
            .map { meta[it] ?: Tex(it, pretty(it), "Other", 12, 60, true) }
            .sortedWith(compareBy({ it.category }, { it.name }))
        list = out
        log("textures: ${out.size} built in")
        return out
    }

    private fun pretty(f: String) = f.substringBeforeLast('.').replace('_', ' ').replace('-', ' ')
        .split(' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercase) }

    fun find(file: String) = builtIns().firstOrNull { it.file == file }

    /** Decoded picture for a "tex:" or "img:" source, scaled down to at most [maxSide]. */
    fun bitmap(src: String, maxSide: Int = 1280): Bitmap? {
        cache[src]?.let { return it }
        val bmp = runCatching {
            val bytes: ByteArray? = when {
                src.startsWith("tex:") -> TextureLibrary::class.java.classLoader
                    ?.getResourceAsStream("textures/" + src.removePrefix("tex:"))?.use { it.readBytes() }
                src.startsWith("img:") -> File(src.removePrefix("img:")).takeIf { it.exists() }?.readBytes()
                else -> null
            }
            bytes?.let { decode(it, maxSide) }
        }.onFailure { log("texture load failed '$src': $it") }.getOrNull() ?: return null
        if (cache.size > 6) cache.clear()
        cache[src] = bmp
        return bmp
    }

    /** A small preview for the picker grid. */
    fun thumb(src: String): Bitmap? {
        val key = "thumb|$src"
        cache[key]?.let { return it }
        val b = bitmap(src, 1280) ?: return null
        val s = 160f / maxOf(b.width, b.height)
        val t = Bitmap.createScaledBitmap(b, (b.width * s).toInt().coerceAtLeast(1), (b.height * s).toInt().coerceAtLeast(1), true)
        cache[key] = t
        return t
    }

    private fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---- the viewer's own pictures ----

    private const val REQ = 0x5054
    private var onPicked: ((String) -> Unit)? = null
    @Volatile private var hooked = false

    fun userDir(ctx: Context) = File(ctx.filesDir, "ptv_textures").apply { mkdirs() }

    fun userImages(ctx: Context): List<String> =
        userDir(ctx).listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }
            ?.map { "img:" + it.absolutePath }.orEmpty()

    fun delete(src: String) {
        if (src.startsWith("img:")) runCatching { File(src.removePrefix("img:")).delete() }
        cache.keys.filter { it.endsWith(src) }.forEach { cache.remove(it) }
    }

    /** Open the system picture picker; [done] gets the new "img:" source once it is copied in. */
    fun pickFromDevice(act: Activity, done: (String) -> Unit) {
        installResultHook()
        onPicked = done
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { act.startActivityForResult(Intent.createChooser(i, "Pick a picture"), REQ) }
            .onFailure { log("picture picker failed: $it") }
    }

    /**
     * The result comes back to Twitch's activity. Activity.dispatchActivityResult is the framework
     * method that delivers every result, so catching our request code there works whatever
     * Twitch's own activity code is called.
     */
    private fun installResultHook() {
        if (hooked) return
        hooked = true
        XposedBridge.hookAllMethods(Activity::class.java, "dispatchActivityResult", object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val code = param.args.getOrNull(1) as? Int ?: return
                if (code != REQ) return
                val act = param.thisObject as Activity
                val result = param.args.getOrNull(2) as? Int
                val data = param.args.getOrNull(3) as? Intent
                param.result = null
                val uri = data?.data
                if (result != Activity.RESULT_OK || uri == null) return
                Thread({
                    val src = runCatching { copyIn(act, uri) }.onFailure { log("picture copy failed: $it") }.getOrNull()
                    if (src != null) act.runOnUiThread { onPicked?.invoke(src) }
                }, "ptv-pick").start()
            }
        })
    }

    private fun copyIn(ctx: Context, uri: Uri): String {
        val bytes = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        val bmp = decode(bytes, 1600) ?: error("not a picture")
        val f = File(userDir(ctx), "pic_${System.currentTimeMillis()}.jpg")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return "img:" + f.absolutePath
    }
}
