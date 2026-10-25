package tv.purple.xp

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * Obfuscation-resolution layer (task #8).
 *
 * R8 full-mode renames classes/methods every Twitch release, so the obfuscated
 * anchors below are pinned to a known-good version and MUST be re-fingerprinted
 * when bumping. The readable `tv.twitch.android.shared.chat.pub.messages.data.*`
 * model classes are R8-kept and stable across releases, so those are referenced by
 * their real names and are the load-bearing part of the design.
 *
 * Verified chain on Twitch 31.1.2:
 *   kn5.a(String, List): List<MessageToken>     // tokenizer  -> inject here
 *     -> ao5.a(...)                             // MessageToken -> khm UI model
 *       -> e86.j(..., khm, ...): SpannableString// renders the emote span
 *         -> az6.y(az6, Context, String, float): gv0   // builds CDN url -> redirect here
 *           gv0.a:String  = the emote image URL
 */
object Names {
    // --- pinned obfuscated anchors for the LIVE chat render path (Twitch 31.1.2) ---
    // Live chain: v46.b -> e86.b -> the rendered line. Re-fingerprinted for 31.1.2 by finding the
    // only static method returning the line model that also takes the LiveChatMessage. The slot
    // letters below are carried over from 28.6.1, where the model had an identical field shape;
    // they are not yet confirmed on device for this version.
    // v46.b returns the per-chat-line model `f86`; field .e is the message body.
    //   f86.a=timestamp  f86.c="username: "  f86.e=body  f86.f=bool
    val ASSEMBLER: String get() = TwitchVersion.pick("v46", "b86")   // .b(...) -> chat line model (f86 / lb6)
    const val ASSEMBLER_METHOD = "b"
    const val BODY_FIELD = "e"            // f86.e : CharSequence = the message body
    const val NAME_FIELD = "c"            // f86.c : CharSequence = "username: " (badges/pronouns)
    const val BADGE_FIELD = "b"           // f86.b : CharSequence = Twitch's own badges
    const val TIME_FIELD = "a"            // f86.a : CharSequence = timestamp, e.g. "2:42 "

    // --- (legacy/unused) stable readable model classes ---
    const val PKG = "tv.twitch.android.shared.chat.pub.messages.data"
    const val TEXT_TOKEN = "$PKG.MessageToken\$TextToken"
    const val EMOTICON_TOKEN = "$PKG.MessageToken\$EmoticonToken"

    /** Sentinel marking an id we injected; az6.y hook redirects these to 7TV/BTTV/FFZ CDN. */
    const val SENTINEL = "PTV"

    fun emoticonId(imageUrl: String) = SENTINEL + imageUrl
    fun isOurs(id: String?) = id != null && id.startsWith(SENTINEL)
    fun urlOf(id: String) = id.substring(SENTINEL.length)

    /** Resolve an anchor class, logging clearly if the rename pin is stale. */
    fun cls(lp: LoadPackageParam, name: String): Class<*>? = try {
        lp.classLoader.loadClass(name)
    } catch (t: Throwable) {
        log("MISSING anchor class '$name': obfuscation pin is stale, re-fingerprint needed")
        null
    }
}
