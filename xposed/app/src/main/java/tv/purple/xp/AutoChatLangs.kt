package tv.purple.xp

import android.content.Context
import android.net.ConnectivityManager
import java.util.concurrent.ConcurrentHashMap

/**
 * "Auto" in "Languages chat is written in": when chat keeps coming in a language that has its own
 * into English model, that model is downloaded by itself, so its lines are translated better from
 * then on. Only on Wi-Fi or another unmetered network, one language at a time, at most once per
 * language while Twitch runs (a failed download is not retried until the next start).
 */
object AutoChatLangs {

    const val KEY = "translate_chat_auto"
    /** Lines in one language before its model is fetched: a stray line is not enough. */
    private const val LINES = 15

    private val counts = ConcurrentHashMap<String, Int>()
    private val tried = ConcurrentHashMap.newKeySet<String>()

    fun on(): Boolean = Settings.isReady() && Settings.get(KEY, false)

    /** From the translator: a line in chat language [lang] went through the general model. Any thread. */
    fun seen(ctx: Context, lang: String) {
        if (!on() || lang in tried) return
        val n = counts.merge(lang, 1, Int::plus) ?: 1
        if (n < LINES || Translator.downloading() || !TranslatePacks.supported()) return
        if (TranslatePacks.chatLangReady(ctx, lang)) return
        if (!unmetered(ctx)) return
        if (!tried.add(lang)) return
        val mb = (TranslatePacks.chatLangBytes(ctx, listOf(lang)) + 500_000) / 1_000_000
        AdTime.toast("Chat is often in ${Translator.name(lang)}: getting its translation model ($mb MB)")
        TranslatePacks.downloadChatLangs(ctx, listOf(lang), {}) { ok, msg ->
            if (ok) { Translator.clearCache(); ChatTranslate.clearCache() }
            AdTime.toast(msg)
        }
    }

    private fun unmetered(ctx: Context): Boolean = runCatching {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return false
        cm.activeNetwork != null && !cm.isActiveNetworkMetered
    }.getOrDefault(false)
}
