package tv.purple.xp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import java.util.WeakHashMap

/**
 * Full video filters where RuntimeShader is missing (Android 12 and older), through our own GL
 * pass between the decoder and the screen.
 *
 * Twitch gives its player a Surface made with `new Surface(textureView.getSurfaceTexture())`. We
 * hook that framework constructor: when the SurfaceTexture belongs to the player's TextureView, the
 * player is handed a Surface for OUR SurfaceTexture instead. Each decoded frame then lands in our
 * texture, our shader (the same filter body as the RuntimeShader path, see [FilterShader]) draws
 * it into the TextureView's real SurfaceTexture, and the TextureView shows it as usual.
 *
 * If anything goes wrong, the player is moved straight back onto the real SurfaceTexture through
 * Twitch's own listener, so the video never stays black, and the colour-matrix fallback takes over.
 */
object GlVideoFilter {

    /** Hidden test switch: use this path on Android 13+ too. */
    const val KEY_FORCE = "vf_force_gl"

    private val main = Handler(Looper.getMainLooper())
    private val pipes = WeakHashMap<SurfaceTexture, Pipe>()
    private val byView = WeakHashMap<TextureView, Pipe>()
    @Volatile private var broken = false
    @Volatile private var bypass = false
    /** Fallbacks in a row with no good frame in between. Kept outside the pipes, because every
     *  retry builds a new pipe, so a count inside one never reached the give-up limit. */
    @Volatile private var failures = 0

    /** Whether this device should use the GL path at all. */
    private fun wanted() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || Settings.get(KEY_FORCE, false)

    fun available(): Boolean = wanted() && !broken && Gl.ok

    fun install() {
        if (!wanted()) return
        XposedHelpers.findAndHookConstructor(Surface::class.java, SurfaceTexture::class.java, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (broken || bypass || Thread.currentThread() === Gl.thread) return
                val real = p.args[0] as? SurfaceTexture ?: return
                val pipe = synchronized(pipes) { pipes[real] } ?: newPipe(real) ?: return
                p.args[0] = pipe.input
            }
        })
        Gl.warmUp()
        log("GL filter path installed")
    }

    private fun newPipe(real: SurfaceTexture): Pipe? {
        if (Looper.myLooper() !== Looper.getMainLooper()) return null
        if (!Gl.ok || !VideoFilters.anyActive()) return null
        val tv = findPlaybackView(real) ?: return null
        return makePipe(tv, real)
    }

    private fun makePipe(tv: TextureView, real: SurfaceTexture): Pipe {
        val pipe = Pipe(tv, real)
        synchronized(pipes) { pipes[real] = pipe }
        byView[tv] = pipe
        tv.addOnAttachStateChangeListener(pipe.attachListener)
        pipe.params = Params.snapshot(tv)
        Gl.start(pipe)
        log("GL filter pipe started for ${tv.width}x${tv.height}")
        return pipe
    }

    /** The player TextureView that owns [st], if it is one: exact class, inside playback_view_container. */
    private fun findPlaybackView(st: SurfaceTexture): TextureView? {
        val root = ViewHider.currentActivity()?.window?.decorView ?: return null
        val cid = root.resources.getIdentifier("playback_view_container", "id", root.context.packageName)
        var found: TextureView? = null
        fun walk(v: View, inside: Boolean) {
            if (found != null) return
            val inn = inside || v.id == cid
            if (inn && v is TextureView && v.javaClass == TextureView::class.java && v.surfaceTexture === st) { found = v; return }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), inn)
        }
        walk(root, false)
        return found
    }

    /** Called by VideoFilters when the filters change: new values, or switched on or off. */
    fun update(tv: TextureView, on: Boolean) {
        val p = byView[tv]
        if (p == null) {
            // Switched on during playback: ask Twitch's own listener for a fresh Surface, which our
            // hook then swaps, moving the decoder over to our texture.
            if (on && Gl.ok && !broken) {
                val real = tv.surfaceTexture ?: return
                if (real.isReleasedCompat()) return
                makePipe(tv, real)
                replumb(tv, real, swap = true)
            }
            return
        }
        p.params = if (on) Params.snapshot(tv) else null
        Gl.redraw(p)
    }

    /** Ask the TextureView's own listener to hand the player a new Surface for [real]. */
    private fun replumb(tv: TextureView, real: SurfaceTexture, swap: Boolean) {
        val l = tv.surfaceTextureListener ?: return
        bypass = !swap
        try { l.onSurfaceTextureAvailable(real, tv.width, tv.height) } catch (t: Throwable) { log("GL replumb failed: $t") }
        finally { bypass = false }
    }

    /** Move the player straight back onto the real SurfaceTexture and drop our pipe. */
    fun fallback(p: Pipe, why: String) {
        log("GL filter fallback: $why")
        Gl.dropWindow(p) {
            main.post {
                synchronized(pipes) { if (pipes[p.real] === p) pipes.remove(p.real) }
                if (byView[p.tv] === p) byView.remove(p.tv)
                if (!p.real.isReleasedCompat() && p.tv.surfaceTexture === p.real) replumb(p.tv, p.real, swap = false)
                main.postDelayed({ Gl.teardown(p) }, 1000)
                if (++failures >= 2 || why.startsWith("start")) broken = true
                VideoFilters.refresh(p.tv)
            }
        }
    }

    private fun SurfaceTexture.isReleasedCompat(): Boolean = isReleased

    class Pipe(val tv: TextureView, val real: SurfaceTexture) {
        val input = SurfaceTexture(false).apply { setDefaultBufferSize(maxOf(1, tv.width), maxOf(1, tv.height)) }
        var oes = 0
        var win: EGLSurface = EGL14.EGL_NO_SURFACE
        val stm = FloatArray(16)
        var hasFrame = false
        @Volatile var dead = false
        @Volatile var params: Params? = null
        var ovlTex = 0; var ovlKey: String? = null
        var gradTex = 0; var gradKey: String? = null
        var monoTex = 0; var monoKey: String? = null
        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                // TextureView has already told Twitch the surface is gone and released it.
                main.postDelayed({ if (real.isReleased) Gl.teardown(this@Pipe) }, 50)
            }
        }
    }

    /** Everything one frame needs, gathered on the UI thread. */
    class Params(
        val uniforms: Map<String, FloatArray>,
        val ovlKey: String, val ovlBitmap: Bitmap?, val ovlColor: Int, val ovlTiled: Boolean, val ovlScale: Float,
        val grad: IntArray, val mono: IntArray, val animatedGrain: Boolean, val active: Set<String>
    ) {
        companion object {
            fun snapshot(tv: TextureView): Params {
                val w = tv.width.coerceAtLeast(1); val h = tv.height.coerceAtLeast(1)
                val src = VideoFilters.overlaySource()
                val bmp = if (src.startsWith("color:")) null else TextureLibrary.bitmap(src)
                val color = if (src.startsWith("color:")) src.removePrefix("color:").toIntOrNull() ?: Color.MAGENTA else 0
                val gi = FilterDefs.index("grain")
                val tiled = VideoFilters.overlayTiled()
                // Same defaults as the RuntimeShader path: a pattern at half size, a picture covering the video.
                return Params(VideoFilters.uniforms(w, h), src + "|" + (bmp?.generationId ?: color), bmp, color,
                    tiled, FilterDefs.extraInt("ovl", "scale", if (tiled) 50 else 100).coerceIn(10, 400) / 100f,
                    VideoFilters.gradientColors(), VideoFilters.monoColors(),
                    FilterDefs.amount(gi) > 0 && FilterDefs.extraBool("grain", "animated", true),
                    VideoFilters.activeKeys())
            }
        }
    }

    /** The GL thread: one EGL context shared by every pipe. */
    object Gl {
        private val ht = HandlerThread("ptv-gl", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val thread: Thread get() = ht
        val h = Handler(ht.looper)
        private var dpy: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var cfg: EGLConfig? = null
        private var ctx: EGLContext = EGL14.EGL_NO_CONTEXT
        private var pbuf: EGLSurface = EGL14.EGL_NO_SURFACE
        @Volatile var ok = false
        private var prog = 0
        private var vao = 0
        private val locs = HashMap<String, Int>()
        /** Programs built for a set of active filters (see FilterShader.glsl), by set. */
        private val progs = HashMap<String, Int>()
        private var seed = 0f

        fun warmUp() = h.post {
            runCatching {
                dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val v = IntArray(2); check(EGL14.eglInitialize(dpy, v, 0, v, 1))
                val attrs = intArrayOf(EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE)
                val cs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
                check(EGL14.eglChooseConfig(dpy, attrs, 0, cs, 0, 1, n, 0) && n[0] > 0) { "no EGL config" }
                cfg = cs[0]
                ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT,
                    intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
                check(ctx != EGL14.EGL_NO_CONTEXT) { "no EGL context" }
                pbuf = EGL14.eglCreatePbufferSurface(dpy, cfg, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
                check(EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx))
                prog = programFor(emptySet())
                val va = IntArray(1); GLES30.glGenVertexArrays(1, va, 0); vao = va[0]
                ok = true
                log("GL filter ready: ${GLES30.glGetString(GLES30.GL_RENDERER)}")
            }.onFailure { log("GL warm-up failed: $it"); broken = true }
        }

        private fun compile(type: Int, src: String): Int {
            val s = GLES30.glCreateShader(type)
            GLES30.glShaderSource(s, src); GLES30.glCompileShader(s)
            val st = IntArray(1); GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, st, 0)
            if (st[0] == 0) error("shader: " + GLES30.glGetShaderInfoLog(s))
            return s
        }

        private fun link(vs: String, fs: String): Int {
            val p = GLES30.glCreateProgram()
            GLES30.glAttachShader(p, compile(GLES30.GL_VERTEX_SHADER, vs))
            GLES30.glAttachShader(p, compile(GLES30.GL_FRAGMENT_SHADER, fs))
            GLES30.glLinkProgram(p)
            val st = IntArray(1); GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, st, 0)
            if (st[0] == 0) error("link: " + GLES30.glGetProgramInfoLog(p))
            return p
        }

        private fun loc(name: String) = locs.getOrPut("$prog:$name") { GLES30.glGetUniformLocation(prog, name) }

        /** The program with only [active] filters compiled in; built once per set, the last few kept. */
        private fun programFor(active: Set<String>): Int {
            val id = active.sorted().joinToString(",")
            progs[id]?.let { return it }
            if (progs.size >= 6) {
                for (old in progs.values) if (old != prog) GLES30.glDeleteProgram(old)
                progs.values.retainAll(setOf(prog)); locs.clear()
            }
            return link(VERT, FilterShader.glsl(active)).also { progs[id] = it }
        }

        fun start(p: Pipe) = h.post {
            try {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); p.oes = t[0]
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.oes)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                p.input.attachToGLContext(p.oes)
                p.input.setOnFrameAvailableListener({ onFrame(p) }, h)
                createWindow(p, 90)
            } catch (t: Throwable) { fallback(p, "start: $t") }
        }

        private fun createWindow(p: Pipe, tries: Int) {
            if (p.dead || p.real.isReleased) return
            val s = EGL14.eglCreateWindowSurface(dpy, cfg, p.real, intArrayOf(EGL14.EGL_NONE), 0)
            if (s == EGL14.EGL_NO_SURFACE) {
                // The decoder is still connected to the real texture (switched on mid-playback).
                // The retry is a bare message on our own thread, where nothing else would catch a throw
                // and it would take Twitch down, so it gets the same guard as the first attempt.
                if (tries > 0) h.postDelayed({
                    try { createWindow(p, tries - 1) } catch (t: Throwable) { fallback(p, "window: $t") }
                }, 16)
                else fallback(p, "window: 0x" + Integer.toHexString(EGL14.eglGetError()))
                return
            }
            p.win = s
            EGL14.eglMakeCurrent(dpy, s, s, ctx)
            EGL14.eglSwapInterval(dpy, 0)
            // Often the first draw of this filter set, so its shader is built here and may be rejected.
            if (p.hasFrame) try { draw(p) } catch (t: Throwable) { fallback(p, "frame: $t") }
        }

        private fun onFrame(p: Pipe) {
            if (p.dead) return
            try {
                val cur = if (p.win != EGL14.EGL_NO_SURFACE) p.win else pbuf
                EGL14.eglMakeCurrent(dpy, cur, cur, ctx)
                p.input.updateTexImage()
                p.input.getTransformMatrix(p.stm)
                p.hasFrame = true
                // Drawn even while hidden: a visibility flag, only refreshed on a filter change, froze the
                // picture once Twitch came back. Swap interval 0 keeps a hidden view from blocking us.
                if (p.win != EGL14.EGL_NO_SURFACE) draw(p)
            } catch (t: Throwable) { fallback(p, "frame: $t") }
        }

        fun redraw(p: Pipe) = h.post { if (!p.dead && p.hasFrame && p.win != EGL14.EGL_NO_SURFACE) runCatching { draw(p) } }

        private fun draw(p: Pipe) {
            EGL14.eglMakeCurrent(dpy, p.win, p.win, ctx)
            val wh = IntArray(2)
            EGL14.eglQuerySurface(dpy, p.win, EGL14.EGL_WIDTH, wh, 0)
            EGL14.eglQuerySurface(dpy, p.win, EGL14.EGL_HEIGHT, wh, 1)
            val w = wh[0].coerceAtLeast(1); val hh = wh[1].coerceAtLeast(1)
            val par0 = p.params
            // Dynamic light: measure the stream a few times a second, before drawing over it.
            if (par0 != null && par0.active.any { it in DynLight.KEYS }) {
                val now = android.os.SystemClock.uptimeMillis()
                if (now - measAt >= 150) { measAt = now; runCatching { measure(p) }.onFailure { log("DL gl measure: $it") } }
            }
            GLES30.glViewport(0, 0, w, hh)
            prog = programFor(par0?.active ?: emptySet())
            GLES30.glUseProgram(prog)
            GLES30.glBindVertexArray(vao)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.oes)
            GLES30.glUniform1i(loc("uVideo"), 0)
            GLES30.glUniformMatrix4fv(loc("uTexMatrix"), 1, false, p.stm, 0)
            val par = par0
            if (par == null) {
                // Off: every filter at zero, so the body passes the picture straight through.
                for (u in FilterShader.FILTER_UNIFORMS) GLES30.glUniform3f(loc(u), 0f, 0f, 1f)
                GLES30.glUniform2f(loc("uSize"), w.toFloat(), hh.toFloat())
            } else {
                for ((k, v) in par.uniforms) {
                    val l = loc(k); if (l < 0) continue
                    if (v.size == 3 && k.startsWith("u")) { GLES30.glUniform3f(l, v[0], v[1], v[2]); continue }
                    when (v.size) {
                        1 -> GLES30.glUniform1f(l, v[0]); 2 -> GLES30.glUniform2f(l, v[0], v[1])
                        else -> GLES30.glUniform3f(l, v[0], v[1], v[2])
                    }
                }
                // Dynamic light changes with the picture, so its values are read fresh for every frame.
                for (k in DynLight.KEYS) {
                    if (k !in par.active) continue
                    val l = loc("f_$k"); if (l < 0) continue
                    val yz = DynLight.yz(k)
                    GLES30.glUniform3f(l, par.uniforms["f_$k"]?.get(0) ?: 0f, yz[0], yz[1])
                }
                // Values were measured on the view; the surface may differ slightly, so use its size.
                GLES30.glUniform2f(loc("uSize"), w.toFloat(), hh.toFloat())
                if (par.animatedGrain) seed = (seed + 1.37f) % 997f
                GLES30.glUniform1f(loc("uSeed"), seed)
                GLES30.glUniform1f(loc("uTime"), VideoFilters.now())
                bindOverlay(p, par, w, hh)
                p.gradTex = ramp(p.gradTex, p.gradKey, par.grad, 2, "uGrad").also { p.gradKey = par.grad.joinToString() }
                p.monoTex = ramp(p.monoTex, p.monoKey, par.mono, 3, "uMono").also { p.monoKey = par.mono.joinToString() }
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            EGLExt.eglPresentationTimeANDROID(dpy, p.win, p.input.timestamp)
            if (!EGL14.eglSwapBuffers(dpy, p.win)) {
                val e = EGL14.eglGetError()
                if (p.real.isReleased) teardownNow(p) else fallback(p, "swap: 0x" + Integer.toHexString(e))
            } else if (failures != 0) failures = 0  // a good frame: earlier failures were passing ones
        }

        private var measAt = 0L
        private var measProg = 0
        private var measFbo = 0
        private var measTex = 0
        private val measBuf = java.nio.ByteBuffer.allocateDirect(DynLight.W * DynLight.H * 4)
            .order(java.nio.ByteOrder.nativeOrder())

        /** Draw the raw stream into a tiny buffer and hand its pixels to DynLight. */
        private fun measure(p: Pipe) {
            if (measProg == 0) measProg = link(VERT, MEAS_FRAG)
            if (measFbo == 0) {
                val t = IntArray(1); GLES30.glGenTextures(1, t, 0); measTex = t[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, measTex)
                GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, DynLight.W, DynLight.H, 0,
                    GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
                setParams(GLES30.GL_CLAMP_TO_EDGE)
                val f = IntArray(1); GLES30.glGenFramebuffers(1, f, 0); measFbo = f[0]
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, measFbo)
                GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, measTex, 0)
            }
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, measFbo)
            GLES30.glViewport(0, 0, DynLight.W, DynLight.H)
            GLES30.glUseProgram(measProg)
            GLES30.glBindVertexArray(vao)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.oes)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(measProg, "uVideo"), 0)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(measProg, "uTexMatrix"), 1, false, p.stm, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            measBuf.clear()
            GLES30.glReadPixels(0, 0, DynLight.W, DynLight.H, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, measBuf)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            DynLight.feedRgba(measBuf, DynLight.W * DynLight.H)
        }

        private fun ramp(tex: Int, key: String?, colors: IntArray, unit: Int, name: String): Int {
            var t = tex
            val k = colors.joinToString()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
            if (t == 0 || k != key) {
                if (t == 0) { val a = IntArray(1); GLES30.glGenTextures(1, a, 0); t = a[0] }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
                setParams(GLES30.GL_CLAMP_TO_EDGE)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, VideoFilters.rampBitmap(colors), 0)
            } else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
            GLES30.glUniform1i(loc(name), unit)
            return t
        }

        private fun bindOverlay(p: Pipe, par: Params, w: Int, h: Int) {
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
            if (p.ovlTex == 0) { val a = IntArray(1); GLES30.glGenTextures(1, a, 0); p.ovlTex = a[0] }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.ovlTex)
            val bmp = par.ovlBitmap
            if (p.ovlKey != par.ovlKey) {
                p.ovlKey = par.ovlKey
                val img = bmp ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(par.ovlColor) }
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, img, 0)
            }
            setParams(if (par.ovlTiled && bmp != null) GLES30.GL_REPEAT else GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glUniform1i(loc("uOvl"), 1)
            val bw = (bmp?.width ?: 1).toFloat(); val bh = (bmp?.height ?: 1).toFloat()
            if (bmp == null) {
                GLES30.glUniform2f(loc("uOvlScale"), 0f, 0f); GLES30.glUniform2f(loc("uOvlOff"), 0.5f, 0.5f)
            } else if (par.ovlTiled) {
                val s = (h / 1080f).coerceAtLeast(0.3f) * par.ovlScale
                GLES30.glUniform2f(loc("uOvlScale"), 1f / (bw * s), 1f / (bh * s)); GLES30.glUniform2f(loc("uOvlOff"), 0f, 0f)
            } else {
                val s = maxOf(w / bw, h / bh) * par.ovlScale
                val tx = (w - bw * s) / 2f; val ty = (h - bh * s) / 2f
                GLES30.glUniform2f(loc("uOvlScale"), 1f / (bw * s), 1f / (bh * s))
                GLES30.glUniform2f(loc("uOvlOff"), -tx / (bw * s), -ty / (bh * s))
            }
        }

        private fun setParams(wrap: Int) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
        }

        fun dropWindow(p: Pipe, then: () -> Unit) = h.post {
            runCatching {
                if (p.win != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                    EGL14.eglDestroySurface(dpy, p.win)
                    p.win = EGL14.EGL_NO_SURFACE
                }
            }
            then()
        }

        fun teardown(p: Pipe) = h.post { teardownNow(p) }

        private fun teardownNow(p: Pipe) {
            if (p.dead) return
            p.dead = true
            runCatching {
                EGL14.eglMakeCurrent(dpy, pbuf, pbuf, ctx)
                if (p.win != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, p.win)
                p.win = EGL14.EGL_NO_SURFACE
                p.input.setOnFrameAvailableListener(null)
                runCatching { p.input.detachFromGLContext() }
                p.input.release()
                val ts = intArrayOf(p.ovlTex, p.gradTex, p.monoTex).filter { it != 0 }.toIntArray()
                if (ts.isNotEmpty()) GLES30.glDeleteTextures(ts.size, ts, 0)
            }
            // A newer pipe may already own the view (Twitch re-parents it and makes a fresh texture)
            // or the same texture, so only drop entries that still point at this one.
            synchronized(pipes) { if (pipes[p.real] === p) pipes.remove(p.real) }
            main.post {
                if (byView[p.tv] === p) byView.remove(p.tv)
                p.tv.removeOnAttachStateChangeListener(p.attachListener)
                p.params = null
            }
            log("GL filter pipe released")
        }

        private const val MEAS_FRAG = "#version 300 es\n#extension GL_OES_EGL_image_external_essl3 : require\n" +
            "precision mediump float;\nin vec2 vUv;\nout vec4 fragColor;\n" +
            "uniform samplerExternalOES uVideo;\nuniform mat4 uTexMatrix;\n" +
            "void main() { fragColor = vec4(texture(uVideo, (uTexMatrix * vec4(vUv, 0.0, 1.0)).xy).rgb, 1.0); }\n"

        private const val VERT = "#version 300 es\n" +
            "out vec2 vUv;\n" +
            "void main() {\n" +
            "  vec2 pos = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);\n" +
            "  vUv = pos * 0.5 + 0.5;\n" +
            "  gl_Position = vec4(pos, 0.0, 1.0);\n" +
            "}\n"
    }
}
