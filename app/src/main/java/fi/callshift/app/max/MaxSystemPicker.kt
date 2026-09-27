package fi.callshift.app.max

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityNodeInfo as Node
import fi.callshift.app.domain.MaxRoutePolicy
import fi.callshift.app.domain.MaxUiPolicy

/** Explicitly taught system chooser only. Never inspects ordinary third-party apps. */
object MaxSystemPicker {
    data class Snapshot(val picker: MaxRoutePolicy.Picker, val buttons: Map<String, Node>)
    fun read(context: Context, root: Node): Snapshot? {
        val pkg = root.packageName?.toString() ?: return null
        if (pkg == MaxUiPolicy.PACKAGE || pkg == context.packageName) return null
        val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() ?: return null
        val flags = info.applicationInfo?.flags ?: return null
        if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return null
        val all = mutableListOf<Node>()
        var truncated = false
        fun walk(n: Node, depth: Int) {
            if (all.size >= 250 || depth > 20) { truncated = true; return }
            all += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        if (truncated || all.size >= 250) return null // truncated trees cannot establish uniqueness
        if (all.none { it.isVisibleToUser && MaxRoutePolicy.chooserTitle(it.text?.toString().orEmpty()) }) return null
        val labels = all.filter { it.isVisibleToUser && !it.isEditable && !it.isPassword &&
            MaxRoutePolicy.candidateLabel(it.text?.toString().orEmpty()) }
        val texts = labels.map { it.text.toString() }
        if (!MaxRoutePolicy.validLabels(texts)) return null
        val buttons = mutableMapOf<String, Node>()
        for (label in labels) {
            var n: Node? = label
            var button: Node? = null
            for (i in 0..3) {
                val current = n ?: break
                if (current == root || current.isEditable || current.isPassword || current.isScrollable || current.collectionInfo != null) break
                if (current.isVisibleToUser && current.isEnabled && current.isClickable) { button = current; break }
                n = current.parent
            }
            val chosen = button ?: return null
            // A common ancestor containing both options is never a selectable row.
            for (other in labels.filter { it != label }) {
                var ancestor: Node? = other
                repeat(24) {
                    if (ancestor == chosen) return null
                    ancestor = ancestor?.parent
                }
            }
            buttons[label.text.toString()] = chosen
        }
        if (buttons.values.distinct().size != 2) return null
        // Fingerprint structure, never UI text. Sorted multiset tolerates option reordering.
        val structure = all.filter { it.isVisibleToUser }.map {
            "${it.className}|${it.viewIdResourceName}|${it.isClickable}|${it.isEditable}|${it.childCount}"
        }.sorted().joinToString("\n")
        val shape = java.security.MessageDigest.getInstance("SHA-256").digest(structure.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return Snapshot(MaxRoutePolicy.Picker(pkg, info.longVersionCode, root.className?.toString().orEmpty(), texts.sorted(), shape), buttons)
    }
}
