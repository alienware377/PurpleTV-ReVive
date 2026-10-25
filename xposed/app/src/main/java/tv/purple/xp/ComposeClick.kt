package tv.purple.xp

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider
import android.widget.TextView

/**
 * Press a button by its visible text, including buttons drawn by Jetpack Compose, which have no
 * View of their own. Compose describes its buttons to Android's accessibility system, so we walk
 * that description (the same one a screen reader uses) and ask it to click the matching node.
 */
object ComposeClick {

    /** Clicks the first view or Compose node under [root] whose text equals [text]. */
    fun clickText(root: View, text: String): Boolean {
        val want = text.trim()
        if (want.isEmpty()) return false
        // Ordinary views first.
        findTextView(root, want)?.let { tv ->
            var p: View? = tv
            while (p != null) { if (p.hasOnClickListeners() && p.isShown) return p.performClick(); p = p.parent as? View }
        }
        // Then Compose hosts.
        return clickInProviders(root, want)
    }

    private fun findTextView(v: View, want: String): TextView? {
        if (v is TextView && v.isShown && v.text?.toString()?.trim().equals(want, true)) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findTextView(v.getChildAt(i), want)?.let { return it }
        return null
    }

    private fun clickInProviders(v: View, want: String): Boolean {
        if (!v.isShown) return false
        val provider = runCatching { v.accessibilityNodeProvider }.getOrNull()
        if (provider != null && clickIn(provider, want)) return true
        if (v is ViewGroup) for (i in 0 until v.childCount) if (clickInProviders(v.getChildAt(i), want)) return true
        return false
    }

    private val childIdMethod by lazy {
        runCatching { AccessibilityNodeInfo::class.java.getDeclaredMethod("getChildId", Int::class.javaPrimitiveType).apply { isAccessible = true } }.getOrNull()
    }

    /** Virtual node id from the long Android packs (window id high bits, virtual id low 32 bits). */
    private fun virtualId(packed: Long): Int = (packed ushr 32).toInt()

    private fun clickIn(provider: AccessibilityNodeProvider, want: String): Boolean {
        val m = childIdMethod ?: return false
        val queue = ArrayDeque<Int>()
        queue.add(View.NO_ID)
        var seen = 0
        while (queue.isNotEmpty() && seen < 600) {
            val id = queue.removeFirst()
            val node = provider.createAccessibilityNodeInfo(id) ?: continue
            seen++
            val label = (node.text ?: node.contentDescription)?.toString()?.trim()
            if (id != View.NO_ID && label != null && label.equals(want, true)) {
                // Click the node itself if it is clickable, otherwise its nearest clickable parent.
                var target = id; var n: AccessibilityNodeInfo? = node; var hops = 0
                while (n != null && !n.isClickable && hops < 5) {
                    val parent = runCatching {
                        AccessibilityNodeInfo::class.java.getDeclaredMethod("getParentNodeId").apply { isAccessible = true }.invoke(n) as Long
                    }.getOrNull() ?: break
                    target = virtualId(parent)
                    n = provider.createAccessibilityNodeInfo(target); hops++
                }
                val ok = provider.performAction(target, AccessibilityNodeInfo.ACTION_CLICK, null)
                log("compose click '$want': $ok")
                return ok
            }
            for (i in 0 until node.childCount) {
                val packed = runCatching { m.invoke(node, i) as Long }.getOrNull() ?: continue
                queue.add(virtualId(packed))
            }
        }
        return false
    }
}
