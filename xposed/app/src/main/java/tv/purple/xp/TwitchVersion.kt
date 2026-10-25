package tv.purple.xp

import android.content.Context

/**
 * Which Twitch build the module is running in, so one module works on every supported version.
 *
 * Twitch's code is renamed by R8 in every release, so the handful of renamed classes the module
 * reaches by name are kept per version and picked here. Readable classes, resource names and
 * Android itself do not change. A Twitch newer than the newest known one uses the newest names
 * (most of them usually survive a minor update); an older one uses 31.1.2's.
 */
object TwitchVersion {

    /** Supported builds, oldest first: version code to name. */
    const val V31_1_2 = 3101026L
    const val V31_7_0 = 3107006L

    @Volatile var code: Long = 0L
        private set
    @Volatile var name: String = "?"
        private set

    fun init(ctx: Context) {
        runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            code = if (android.os.Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
            name = pi.versionName ?: "?"
        }
        log("Twitch $name ($code): using ${if (atLeast317()) "31.7.0" else "31.1.2"} names")
    }

    fun atLeast317() = code >= V31_7_0

    /** The name for this Twitch: [v31] for 31.1.2 (and older), [v317] for 31.7.0 and newer. */
    fun pick(v31: String, v317: String) = if (atLeast317()) v317 else v31

    /** Channel and user ids became Twitch's own Tuid type in 31.7.0's event models. */
    fun channelIdArg(cl: ClassLoader, id: String): Any? =
        if (!atLeast317()) id
        else id.toIntOrNull()?.let {
            runCatching { de.robv.android.xposed.XposedHelpers.newInstance(cl.loadClass("tv.twitch.android.models.Tuid"), id) }.getOrNull()
        }

    /** RxJava's PublishSubject.onNext, renamed between versions. */
    val onNext: String get() = pick("c", "d")
}
