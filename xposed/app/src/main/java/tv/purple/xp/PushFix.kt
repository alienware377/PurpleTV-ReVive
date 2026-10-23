package tv.purple.xp

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import java.net.URLConnection

/**
 * Push notifications on a patched (re-signed) Twitch.
 *
 * Before Twitch can receive pushes, the Firebase SDK inside it registers the install with
 * firebaseinstallations.googleapis.com. That request carries the app's package name and the
 * SHA-1 of its signing certificate, and Twitch's Firebase key only accepts Twitch's own
 * certificate. A patched build is signed with a different key, so registration is refused and no
 * push ever arrives.
 *
 * The fix: on requests to that one host only, the certificate header is given Twitch's
 * published certificate fingerprint instead of the patched one. Nothing else is touched, and on
 * an unpatched install the value is the same one it already had.
 *
 * Hooked on the platform's own HttpURLConnection implementation, which is what the Firebase SDK
 * uses, so nothing obfuscated is involved.
 */
object PushFix {

    const val KEY = "fix_push_notifications"

    private const val HOST = "firebaseinstallations.googleapis.com"
    private const val HEADER = "X-Android-Cert"
    /** SHA-1 of the certificate the official Twitch app is signed with (CN=Alec Lee, O=Twitch). */
    private const val TWITCH_CERT_SHA1 = "8C68C13822723A2B1FA844BED340031BEB1F9463"

    private val IMPL_CLASSES = arrayOf(
        "com.android.okhttp.internal.huc.HttpURLConnectionImpl",
        "com.android.okhttp.internal.huc.HttpsURLConnectionImpl")

    fun install() {
        val hook = object : XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if (!Settings.get(KEY, true)) return
                val name = param.args.getOrNull(0) as? String ?: return
                if (!name.equals(HEADER, ignoreCase = true)) return
                val conn = param.thisObject as? URLConnection ?: return
                if (conn.url?.host != HOST) return
                if (param.args[1] != TWITCH_CERT_SHA1) {
                    param.args[1] = TWITCH_CERT_SHA1
                    log("push fix: Firebase registration uses Twitch's certificate")
                }
            }
        }
        var n = 0
        for (cn in IMPL_CLASSES) {
            val c = runCatching { Class.forName(cn) }.getOrNull() ?: continue
            for (m in arrayOf("setRequestProperty", "addRequestProperty"))
                runCatching { n += XposedBridge.hookAllMethods(c, m, hook).size }
        }
        log("push fix hooks: $n")
    }
}
