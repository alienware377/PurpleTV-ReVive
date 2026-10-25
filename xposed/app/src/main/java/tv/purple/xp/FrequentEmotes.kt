package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.util.concurrent.ConcurrentHashMap

/**
 * Let emotes sent from our favorites bar and suggestion list count towards Twitch's
 * "Frequently Used" row, the same as emotes tapped in Twitch's own picker.
 *
 * Twitch counts at send time, but only for emotes clicked in its picker since the last send: the
 * picker pushes an EmoteClicked event into the frequent-emotes repository, and the repository's
 * send method then matches the message words against that list. So just before that send method
 * runs, we push the same event for every Twitch emote our UI inserted that is still in the text.
 * Twitch then does the counting, the 50-row cap and the database write itself.
 *
 * Three obfuscated names are pinned for Twitch 31.1.2 (see the re-fingerprint notes in
 * [Names]): the repository, the click event and the generic emote model.
 */
object FrequentEmotes {

    private const val REPO = "qqf"         // frequent-emotes repository; "SeemsGood"/"64138" in <clinit>
    private const val REPO_SEND = "e"      // e(String messageText): counts the clicked emotes
    private const val CLICK_EVENT = "oqf"  // "EmoteClicked(emoteModel="
    private const val GENERIC = "mnf"      // "Generic(id=": EmoteModel.Generic(id, token)
    private const val DISPATCHER = "tv.twitch.android.core.mvp.viewdelegate.EventDispatcher"

    private val pending: MutableSet<String> = ConcurrentHashMap.newKeySet()
    /** The repository Twitch made, kept for the hidden test below. */
    @Volatile private var repoRef: java.lang.ref.WeakReference<Any>? = null
    @Volatile private var sendMethod: java.lang.reflect.Method? = null
    private val ID_RE = Regex("/emoticons/v[12]/([^/]+)/")

    /** Called when our UI puts [name] into the chat box. Only Twitch emotes can be counted. */
    fun noteInserted(name: String) {
        if (EmoteRepo.twitch.containsKey(name)) pending.add(name)
    }

    fun install(lp: LoadPackageParam) {
        val cl = lp.classLoader
        val repo = runCatching { cl.loadClass(REPO) }.getOrNull() ?: run { log("FE repository not found"); return }
        val evCtor = cl.loadClass(CLICK_EVENT).declaredConstructors.first { it.parameterTypes.size == 1 }
            .apply { isAccessible = true }
        val genCtor = cl.loadClass(GENERIC).getDeclaredConstructor(String::class.java, String::class.java)
            .apply { isAccessible = true }
        val dispCls = cl.loadClass(DISPATCHER)
        val dispField = repo.declaredFields.first { it.type == dispCls }.apply { isAccessible = true }
        // The push method is found by its shape, (Object)V, not by its letter.
        val push = dispCls.declaredMethods.first {
            it.returnType == Void.TYPE && it.parameterTypes.contentEquals(arrayOf<Class<*>>(Any::class.java))
        }.apply { isAccessible = true }
        de.robv.android.xposed.XposedBridge.hookAllConstructors(repo, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) { repoRef = java.lang.ref.WeakReference(p.thisObject) }
        })
        sendMethod = runCatching { repo.getDeclaredMethod(REPO_SEND, String::class.java).apply { isAccessible = true } }.getOrNull()
        XposedHelpers.findAndHookMethod(repo, REPO_SEND, String::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                repoRef = java.lang.ref.WeakReference(p.thisObject)
                if (pending.isEmpty()) return
                // One send consumes the list, exactly like Twitch's own clicked-emotes list.
                val names = pending.toList(); pending.clear()
                val words = (p.args[0] as? String)?.split(' ')?.toHashSet() ?: return
                val disp = dispField.get(p.thisObject) ?: return
                var n = 0
                for (name in names) {
                    if (name !in words) continue
                    val id = EmoteRepo.twitch[name]?.let { ID_RE.find(it)?.groupValues?.get(1) } ?: continue
                    runCatching { push.invoke(disp, evCtor.newInstance(genCtor.newInstance(id, name))); n++ }
                        .onFailure { log("FE push failed: $it") }
                }
                if (n > 0) log("FE counted $n emote(s) towards Frequently Used")
            }
        })
        log("FE frequent-emotes hook installed")
    }

    /**
     * Hidden test (DEV_FREQ): act as if [name] was inserted by our UI and a message holding only
     * it was sent, by calling the repository's counting step directly. No chat message is sent.
     * Logs the emote's Frequently Used count before and after.
     */
    fun test(ctx: android.content.Context, name: String) {
        val repo = repoRef?.get() ?: run { log("FE test: repository not created yet (open a chat first)"); return }
        val m = sendMethod ?: run { log("FE test: no send method"); return }
        val before = count(ctx, name)
        noteInserted(name)
        if (name !in pending) { log("FE test: '$name' is not a Twitch emote of yours"); return }
        runCatching { m.invoke(repo, name) }.onFailure { log("FE test: send step failed: $it") }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            Thread { log("FE test: '$name' times_used before=$before after=${count(ctx, name)}") }.start()
        }, 1500)
    }

    private fun count(ctx: android.content.Context, name: String): String = runCatching {
        val f = ctx.getDatabasePath("emotes.db")
        android.database.sqlite.SQLiteDatabase.openDatabase(f.path, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT times_used, time_accessed FROM frequent_emotes WHERE token = ?", arrayOf(name)).use { c ->
                if (c.moveToFirst()) "${c.getInt(0)} (at ${c.getLong(1)})" else "none"
            }
        }
    }.getOrElse { "error $it" }
}
