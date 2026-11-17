package tv.purple.xp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.io.File

/**
 * Colour looks (3D LUTs) for the LUT filter, from .cube files the viewer picks: the format
 * Resolve, Premiere, Photoshop and most LUT packs export.
 *
 * Each picked file is checked and copied into Twitch's private files folder, so it keeps working
 * after the picker's permission ends. For the shader a LUT of size N becomes a picture N*N wide
 * and N high: blue picks the N wide slice, red and green the pixel inside it.
 */
object LutLibrary {

    /** The largest LUT accepted: 64 makes a 4096 x 64 picture, which every GPU can hold. */
    private const val MAX_SIZE = 64
    private const val REQ = 0x5055
    private var onPicked: ((File?, String?) -> Unit)? = null
    @Volatile private var hooked = false

    fun dir(ctx: Context) = File(ctx.filesDir, "ptv_luts").apply { mkdirs() }

    fun list(ctx: Context): List<File> =
        dir(ctx).listFiles()?.filter { it.isFile && it.name.endsWith(".cube") }?.sortedBy { it.name.lowercase() }.orEmpty()

    fun name(path: String) = File(path).name.removeSuffix(".cube").replace('_', ' ')

    fun delete(path: String) {
        runCatching { File(path).delete() }
        synchronized(this) { if (cachedPath == path) { cachedPath = null; cached = null } }
    }

    // ---- the picture the shader reads ----

    private var cachedPath: String? = null
    private var cachedStamp = 0L
    private var cached: Bitmap? = null

    /** The chosen LUT as the shader's picture, or null when none is chosen or it cannot be read. */
    @Synchronized
    fun bitmap(path: String): Bitmap? {
        if (path.isEmpty()) return null
        val f = File(path)
        if (!f.isFile) return null
        if (path == cachedPath && f.lastModified() == cachedStamp) return cached
        cachedPath = path; cachedStamp = f.lastModified()
        cached = runCatching { parse(f.readText())?.let { atlas(it) } }.onFailure { log("LUT read failed: $it") }.getOrNull()
        return cached
    }

    class Cube(val size: Int, val rgb: FloatArray)

    /** A .cube file's 3D table, scaled to 0..1, or null when it is not one this can use. */
    fun parse(text: String): Cube? {
        var size = 0
        val lo = floatArrayOf(0f, 0f, 0f); val hi = floatArrayOf(1f, 1f, 1f)
        var rgb: FloatArray? = null
        var n = 0
        for (raw in text.lineSequence()) {
            val line = raw.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0].uppercase()) {
                "TITLE", "LUT_1D_INPUT_RANGE" -> {}
                "LUT_1D_SIZE" -> return null
                "LUT_3D_SIZE" -> {
                    size = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    if (size < 2 || size > MAX_SIZE) return null
                    rgb = FloatArray(size * size * size * 3)
                }
                "DOMAIN_MIN" -> for (k in 0..2) lo[k] = parts.getOrNull(k + 1)?.toFloatOrNull() ?: return null
                "DOMAIN_MAX" -> for (k in 0..2) hi[k] = parts.getOrNull(k + 1)?.toFloatOrNull() ?: return null
                "LUT_3D_INPUT_RANGE" -> {
                    val a = parts.getOrNull(1)?.toFloatOrNull() ?: return null
                    val b = parts.getOrNull(2)?.toFloatOrNull() ?: return null
                    for (k in 0..2) { lo[k] = a; hi[k] = b }
                }
                else -> {
                    val t = rgb ?: return null
                    if (parts.size < 3 || n >= t.size) return null
                    for (k in 0..2) {
                        val v = parts[k].toFloatOrNull() ?: return null
                        val span = hi[k] - lo[k]
                        t[n + k] = ((v - lo[k]) / (if (span != 0f) span else 1f)).coerceIn(0f, 1f)
                    }
                    n += 3
                }
            }
        }
        val t = rgb ?: return null
        return if (n == t.size) Cube(size, t) else null
    }

    /** The table as a picture N*N wide and N high. In a .cube file red changes fastest, then green, then blue. */
    fun atlas(c: Cube): Bitmap {
        val n = c.size
        val px = IntArray(n * n * n)
        var i = 0
        for (b in 0 until n) for (g in 0 until n) for (r in 0 until n) {
            val o = i * 3
            px[g * n * n + b * n + r] = android.graphics.Color.rgb(
                Math.round(c.rgb[o] * 255f), Math.round(c.rgb[o + 1] * 255f), Math.round(c.rgb[o + 2] * 255f))
            i++
        }
        return Bitmap.createBitmap(px, n * n, n, Bitmap.Config.ARGB_8888)
    }

    // ---- picking a file ----

    /** Open the system file picker; [done] gets the copied file, or null and why it was refused. */
    fun pick(act: Activity, done: (File?, String?) -> Unit) {
        installResultHook()
        onPicked = done
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }
        runCatching { act.startActivityForResult(Intent.createChooser(i, "Pick a .cube file"), REQ) }
            .onFailure { log("LUT picker failed: $it") }
    }

    /** As in [TextureLibrary]: every activity result passes Activity.dispatchActivityResult. */
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
                    val r = runCatching { copyIn(act, uri) }
                    act.runOnUiThread {
                        r.onSuccess { onPicked?.invoke(it, null) }
                            .onFailure { e -> log("LUT copy failed: $e"); onPicked?.invoke(null, e.message) }
                    }
                }, "ptv-lut").start()
            }
        })
    }

    private fun copyIn(ctx: Context, uri: Uri): File {
        val r = ctx.contentResolver
        val len = runCatching { r.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getLong(0) else -1L } }.getOrNull() ?: -1L
        if (len > 40_000_000L) error("That file is too big for a LUT")
        val text = r.openInputStream(uri)!!.use { String(it.readBytes(), Charsets.UTF_8) }
        if (parse(text) == null) error("That is not a 3D .cube LUT this can use (sizes 2 to $MAX_SIZE)")
        val shown = runCatching { r.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null } }.getOrNull()
        val base = (shown ?: "LUT").removeSuffix(".cube").removeSuffix(".CUBE")
            .replace(Regex("[^\\p{L}\\p{N} ._-]"), "_").take(60).ifBlank { "LUT" }
        var f = File(dir(ctx), "$base.cube")
        var k = 2
        while (f.exists()) f = File(dir(ctx), "$base ${k++}.cube")
        f.writeText(text)
        return f
    }
}
