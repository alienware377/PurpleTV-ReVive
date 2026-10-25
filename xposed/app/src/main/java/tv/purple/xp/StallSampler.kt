package tv.purple.xp

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/**
 * Hidden test tool (setting "dev_stall_sampler"): when the UI thread stops answering for more
 * than 40 ms, note what it is doing, and every 5 seconds log the most common answers. This is how
 * a stutter is traced to the code causing it on a device that cannot be profiled.
 */
object StallSampler {

    const val KEY = "dev_stall_sampler"
    @Volatile private var beat = 0L

    fun start() {
        if (!Settings.get(KEY, false)) return
        val main = Looper.getMainLooper().thread
        val h = Handler(Looper.getMainLooper())
        h.post(object : Runnable {
            override fun run() { beat = SystemClock.uptimeMillis(); h.postDelayed(this, 10) }
        })
        Thread({
            val counts = HashMap<String, Int>()
            var stalls = 0
            var last = SystemClock.uptimeMillis()
            while (true) {
                Thread.sleep(15)
                val now = SystemClock.uptimeMillis()
                if (beat != 0L && now - beat > 40) {
                    stalls++
                    val st = main.stackTrace
                    val top = st.take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName }
                    val ours = st.firstOrNull { it.className.startsWith("tv.purple.xp") }
                        ?.let { " | ours " + it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber } ?: ""
                    val key = top + ours
                    counts[key] = (counts[key] ?: 0) + 1
                }
                if (now - last > 5000) {
                    if (stalls > 0) {
                        log("STALL $stalls samples in 5s")
                        for ((k, n) in counts.entries.sortedByDescending { it.value }.take(6)) log("STALL $n x $k")
                    }
                    counts.clear(); stalls = 0; last = now
                }
            }
        }, "ptv-stall").apply { isDaemon = true }.start()
        log("stall sampler on")
    }
}
