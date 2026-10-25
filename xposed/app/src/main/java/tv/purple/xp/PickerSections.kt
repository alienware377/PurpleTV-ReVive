package tv.purple.xp

import android.content.Context
import android.graphics.drawable.Animatable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam
import java.lang.ref.WeakReference
import java.lang.reflect.Constructor
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy
import java.util.WeakHashMap

/**
 * Adds 7TV, BTTV, FFZ and Homies sections to the bottom of Twitch's emote picker: this channel's
 * emotes first, then the global ones. Empty sections are left out.
 *
 * The picker grid ("emote_palette") is a RecyclerView fed by Twitch's generic sectioned adapter.
 * Our sections are REAL instances of Twitch's own picker section class, so the headers, sticky
 * headers, spacing and column count are Twitch's own. Each emote cell is a small Proxy implementing
 * Twitch's item interface. Everything obfuscated is found at runtime by its shape, starting from
 * RecyclerView.setAdapter (a name R8 keeps); if a future Twitch changes a shape, discovery fails
 * once, is logged, and the feature simply stays off.
 */
object PickerSections {

    const val KEY = "picker_sections"
    private const val RES_PKG = "tv.twitch.android.app"
    private val main = Handler(Looper.getMainLooper())

    private class Shapes(
        val notifyChanged: Method, val sectionsField: Field, val addSection: Method, val itemsField: Field,
        val itemIface: Class<*>, val viewTypeM: Method, val generatorM: Method, val genIface: Class<*>,
        val genM: Method, val itemViewField: Field, val headerClass: Class<*>, val headerCtor: Constructor<*>,
        val headerModelField: Field, val headerIsChannel: Method, val headerBind: Method
    )

    @Volatile private var shapes: Shapes? = null
    @Volatile private var failed = false
    private var holderCtor: Constructor<*>? = null

    private class State(val rv: WeakReference<ViewGroup>) {
        var current: List<Any> = emptyList()
        var builtVersion = Int.MIN_VALUE
        var cellGen: Any? = null
    }

    private val STATES = WeakHashMap<Any, State>()
    private val TITLES = WeakHashMap<Any, String>()
    private var guard = false
    private lateinit var rvCls: Class<*>

    fun install(lp: LoadPackageParam) {
        rvCls = lp.classLoader.loadClass("androidx.recyclerview.widget.RecyclerView")
        XposedBridge.hookAllMethods(rvCls, "setAdapter", object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                if (failed) return
                val rv = p.thisObject as? ViewGroup ?: return
                val adapter = p.args.getOrNull(0) ?: return
                if (entry(rv) != "emote_palette") return
                STATES.getOrPut(adapter) { State(WeakReference(rv)) }
                tryAttach(rv, adapter, 0)
            }
        })
        EmoteRepo.addPickerListener { main.post { refreshAll() } }
        log("PS picker sections hook installed")
    }

    /** Re-check every live picker, e.g. after emotes load or a setting changes. */
    fun refreshAll() {
        for (a in STATES.keys.toList()) runCatching { ensureTail(a) }.onFailure { log("PS refresh: $it") }
    }

    private fun tryAttach(rv: ViewGroup, adapter: Any, attempt: Int) {
        rv.post {
            runCatching {
                if (discover(adapter, rv) != null) ensureTail(adapter)
                else if (attempt < 20 && !failed) main.postDelayed({ tryAttach(rv, adapter, attempt + 1) }, 250)
            }.onFailure { failed = true; log("PS disabled, Twitch's picker has a different shape: $it") }
        }
    }

    private fun discover(adapter: Any, rv: ViewGroup): Shapes? {
        shapes?.let { return it }
        val aCls = adapter.javaClass
        val secF = (aCls.declaredFields.filter { it.type == ArrayList::class.java }.singleOrNull()
            ?: allFields(aCls).single { it.type == ArrayList::class.java }).apply { isAccessible = true }
        val sections = secF.get(adapter) as List<*>
        val first = sections.firstOrNull() ?: return null
        val adapterBase = rvCls.declaredMethods.first { it.name == "setAdapter" }.parameterTypes[0]
        val notify = adapterBase.declaredMethods.single {
            Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers) &&
                it.parameterTypes.isEmpty() && it.returnType == Void.TYPE
        }.apply { isAccessible = true }
        val add = allMethods(aCls).single { m ->
            !Modifier.isStatic(m.modifiers) && m.returnType == Void.TYPE && m.parameterTypes.size == 1 &&
                m.parameterTypes[0] != Any::class.java && m.parameterTypes[0].isInstance(first)
        }.apply { isAccessible = true }
        val secBase = add.parameterTypes[0]
        val itemsF = secBase.declaredFields.single { it.type == List::class.java }.apply { isAccessible = true }
        val itemIface = secBase.interfaces.single()
        val vt = itemIface.methods.single { it.parameterTypes.isEmpty() && it.returnType == Int::class.javaPrimitiveType }
        val gen = itemIface.methods.single { it.parameterTypes.isEmpty() && it.returnType.isInterface && it.returnType != itemIface }
        val genIface = gen.returnType
        val genM = genIface.methods.single { !it.isDefault && it.parameterTypes.size == 1 && it.parameterTypes[0] == View::class.java }
        var vh: Class<*> = genM.returnType
        while (vh.superclass != null && vh.superclass != Any::class.java) vh = vh.superclass
        val itemView = vh.declaredFields.single {
            it.type == View::class.java && Modifier.isFinal(it.modifiers) && !Modifier.isStatic(it.modifiers)
        }.apply { isAccessible = true }
        val headerLayout = res(rv, "emote_picker_section_header", "layout")
        val header = sections.firstOrNull { it != null && vt.invoke(it) as Int == headerLayout } ?: return null
        val hCls = header.javaClass
        val hCtor = hCls.declaredConstructors.single {
            it.parameterTypes.size == 2 && it.parameterTypes[0] == Context::class.java
        }.apply { isAccessible = true }
        val s = Shapes(
            notify, secF, add, itemsF, itemIface, vt, gen, genIface, genM, itemView, hCls, hCtor,
            hCls.declaredFields.single { it.type == hCtor.parameterTypes[1] }.apply { isAccessible = true },
            hCls.declaredMethods.single { it.parameterTypes.isEmpty() && it.returnType == Boolean::class.javaPrimitiveType }
                .apply { isAccessible = true },
            hCls.declaredMethods.single {
                it.parameterTypes.size == 1 && it.returnType == Void.TYPE && genM.returnType.isAssignableFrom(it.parameterTypes[0])
            }.apply { isAccessible = true }
        )
        installHooks(s)
        shapes = s
        log("PS shapes found: adapter=${aCls.name} section=${hCls.name}")
        return s
    }

    private fun installHooks(s: Shapes) {
        // Keep our sections LAST: drop them before Twitch appends, and put them back once it is done.
        XposedBridge.hookMethod(s.addSection, object : XC_MethodHook() {
            override fun beforeHookedMethod(p: MethodHookParam) {
                if (guard) return
                val st = STATES[p.thisObject] ?: return
                if (st.current.isEmpty()) return
                @Suppress("UNCHECKED_CAST") val list = s.sectionsField.get(p.thisObject) as MutableList<Any?>
                if (list.removeAll { x -> st.current.any { it === x } }) s.notifyChanged.invoke(p.thisObject)
                st.current = emptyList()
            }
            override fun afterHookedMethod(p: MethodHookParam) {
                if (guard) return
                val a = p.thisObject
                if (STATES[a] == null) return
                main.removeCallbacksAndMessages(a)
                main.postAtTime({ runCatching { ensureTail(a) }.onFailure { log("PS tail: $it") } }, a, SystemClock.uptimeMillis())
            }
        })
        // Twitch binds our header from the borrowed model; put our own title on it.
        XposedBridge.hookMethod(s.headerBind, object : XC_MethodHook() {
            override fun afterHookedMethod(p: MethodHookParam) {
                val title = TITLES[p.thisObject] ?: return
                val root = s.itemViewField.get(p.args[0]) as? View ?: return
                (find(root, "emote_section_title") as? TextView)?.text = title
                find(root, "emote_section_sub_text")?.visibility = View.GONE
                find(root, "profile_image")?.visibility = View.GONE
            }
        })
    }

    private fun ensureTail(adapter: Any) {
        val s = shapes ?: return
        val st = STATES[adapter] ?: return
        val rv = st.rv.get() ?: run { STATES.remove(adapter); return }
        @Suppress("UNCHECKED_CAST") val list = s.sectionsField.get(adapter) as MutableList<Any?>
        if (list.isEmpty()) return
        val v = EmoteRepo.pickerVersion() + if (Settings.get(KEY, true)) 0 else 7919
        val wanted = if (v == st.builtVersion) st.current else build(s, st, rv, list)
        st.builtVersion = v
        val n = wanted.size
        val tailOk = n > 0 && list.size >= n && (0 until n).all { list[list.size - n + it] === wanted[it] }
        if (tailOk || (n == 0 && st.current.isEmpty())) return
        guard = true
        try {
            if (list.removeAll { x -> st.current.any { it === x } || wanted.any { it === x } }) s.notifyChanged.invoke(adapter)
            for (sec in wanted) s.addSection.invoke(adapter, sec)
            st.current = wanted
        } finally { guard = false }
    }

    private fun build(s: Shapes, st: State, rv: ViewGroup, twitch: List<Any?>): List<Any> {
        if (!Settings.get(KEY, true)) return emptyList()
        if (holderCtorFor(s, rv, twitch) == null) return emptyList()
        // Borrow a NON-channel Twitch header model, so the bottom "All" tab keeps working.
        val donor = twitch.lastOrNull {
            it != null && s.headerClass.isInstance(it) && TITLES[it] == null && s.headerIsChannel.invoke(it) == false
        } ?: return emptyList()
        val model = s.headerModelField.get(donor)
        val gen = st.cellGen ?: cellGenerator(s, st).also { st.cellGen = it }
        val cellType = res(rv, "emote_picker_container", "layout")
        if (cellType == 0) return emptyList()
        val out = ArrayList<Any>()
        for ((title, names) in EmoteRepo.pickerGroups()) {
            if (names.isEmpty()) continue
            val sec = s.headerCtor.newInstance(rv.context, model)
            s.itemsField.set(sec, ArrayList<Any>(names.map { item(s, gen, cellType, it) }))
            TITLES[sec] = title
            out += sec
        }
        log("PS sections: ${out.size} (${EmoteRepo.pickerGroups().sumOf { it.second.size }} emotes)")
        return out
    }

    private fun item(s: Shapes, gen: Any, cellType: Int, name: String): Any =
        Proxy.newProxyInstance(s.itemIface.classLoader, arrayOf(s.itemIface)) { proxy, m, args ->
            when {
                m.declaringClass == Any::class.java -> objectMethod(proxy, m, args, name)
                m.parameterTypes.isEmpty() && m.returnType == Int::class.javaPrimitiveType -> cellType
                m.parameterTypes.isEmpty() && m.returnType == s.genIface -> gen
                m.parameterTypes.isEmpty() && m.returnType == s.itemIface -> proxy
                m.parameterTypes.size == 1 && m.returnType == Void.TYPE -> {
                    runCatching { bindCell(s, args!![0], name) }.onFailure { log("PS bind: $it") }; null
                }
                else -> zero(m.returnType)
            }
        }

    /** Ignores the throwaway view Twitch inflates for our cell type and builds a real emote cell. */
    private fun cellGenerator(s: Shapes, st: State): Any =
        Proxy.newProxyInstance(s.genIface.classLoader, arrayOf(s.genIface)) { proxy, m, args ->
            if (m.declaringClass == Any::class.java) return@newProxyInstance objectMethod(proxy, m, args, "gen")
            val throwaway = args!![0] as View
            val parent = st.rv.get() ?: (throwaway.parent as? ViewGroup)
            val cell = LayoutInflater.from(throwaway.context)
                .inflate(res(throwaway, "emote_picker_emote_item", "layout"), parent, false)
            cell.addOnAttachStateChangeListener(AnimToggle)
            holderCtor!!.newInstance(cell)
        }

    private fun holderCtorFor(s: Shapes, rv: ViewGroup, twitch: List<Any?>): Constructor<*>? {
        holderCtor?.let { return it }
        val emoteLayout = res(rv, "emote_picker_emote_item", "layout")
        val twitchItem = twitch.asSequence().filterNotNull().filter { TITLES[it] == null }
            .flatMap { (s.itemsField.get(it) as List<*>).asSequence() }
            .firstOrNull { it != null && !Proxy.isProxyClass(it.javaClass) && s.viewTypeM.invoke(it) as Int == emoteLayout }
            ?: return null
        val probe = LayoutInflater.from(rv.context).inflate(emoteLayout, rv, false)
        val holder = s.genM.invoke(s.generatorM.invoke(twitchItem), probe) ?: return null
        return holder.javaClass.getDeclaredConstructor(View::class.java).also { it.isAccessible = true; holderCtor = it }
    }

    private fun bindCell(s: Shapes, holder: Any, name: String) {
        val root = s.itemViewField.get(holder) as View
        val icon = find(root, "emote_icon") as? ImageView ?: return
        (icon.drawable as? Animatable)?.stop()
        val d = EmoteRepo.pickerDrawable(name)
        icon.setImageDrawable(d)
        if (d is Animatable && icon.isAttachedToWindow) d.start()
        if (d == null) EmoteRepo.wantPictures(listOf(name)) { root.post { refreshVisible(root.parent as? ViewGroup, name) } }
        root.tag = name
        icon.contentDescription = name
        icon.setOnClickListener { if (!EmoteAutocomplete.insertEmote(name)) toast(icon.context, "Tap the chat box first") }
        icon.setOnLongClickListener {
            val now = Favorites.toggle(name)
            toast(icon.context, if (now) "★ Added $name to favorites" else "Removed $name from favorites")
            true
        }
        (find(root, "emote_descriptor_icon") as? ImageView)?.let { badge ->
            val animated = EmoteRepo.animBytes.containsKey(name)
            badge.visibility = if (animated) View.VISIBLE else View.GONE
            if (animated) res(badge, "ic_emote_picker_animation_indicator", "drawable").takeIf { it != 0 }?.let { badge.setImageResource(it) }
        }
    }

    private fun refreshVisible(rv: ViewGroup?, name: String) {
        rv ?: return
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            if (c.tag != name) continue
            val icon = find(c, "emote_icon") as? ImageView ?: continue
            val d = EmoteRepo.pickerDrawable(name) ?: continue
            icon.setImageDrawable(d); if (d is Animatable) d.start()
        }
    }

    private object AnimToggle : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) { ((find(v, "emote_icon") as? ImageView)?.drawable as? Animatable)?.start() }
        override fun onViewDetachedFromWindow(v: View) { ((find(v, "emote_icon") as? ImageView)?.drawable as? Animatable)?.stop() }
    }

    // ---- helpers ----

    private val idCache = HashMap<String, Int>()
    private fun res(v: View, name: String, type: String): Int = idCache.getOrPut("$type/$name") {
        val r = v.resources
        r.getIdentifier(name, type, v.context.packageName).takeIf { it != 0 } ?: r.getIdentifier(name, type, RES_PKG)
    }
    private fun find(root: View, entry: String): View? = res(root, entry, "id").takeIf { it != 0 }?.let { root.findViewById(it) }
    private fun entry(v: View): String? = Res.entry(v)
    private fun allFields(c: Class<*>): List<Field> {
        val out = ArrayList<Field>(); var k: Class<*>? = c
        while (k != null && k != Any::class.java) { out.addAll(k.declaredFields); k = k.superclass }
        return out
    }
    private fun allMethods(c: Class<*>): List<Method> {
        val out = ArrayList<Method>(); var k: Class<*>? = c
        // Only the adapter's own class: the section-append method is declared there.
        out.addAll(k!!.declaredMethods)
        return out
    }
    private fun objectMethod(proxy: Any, m: Method, args: Array<out Any?>?, label: String): Any? = when (m.name) {
        "hashCode" -> System.identityHashCode(proxy)
        "equals" -> proxy === args?.getOrNull(0)
        else -> "PTV($label)"
    }
    private fun zero(t: Class<*>): Any? = when (t) {
        Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 0L
        Boolean::class.javaPrimitiveType -> false; Float::class.javaPrimitiveType -> 0f
        Double::class.javaPrimitiveType -> 0.0; else -> null
    }
}
