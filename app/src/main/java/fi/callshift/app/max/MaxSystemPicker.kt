package fi.callshift.app.max

import android.content.Context
import android.content.pm.ApplicationInfo
import android.view.accessibility.AccessibilityNodeInfo as Node
import fi.callshift.app.domain.MaxPickerInspection
import fi.callshift.app.domain.MaxPickerInspection.Issue
import fi.callshift.app.domain.MaxRoutePolicy
import fi.callshift.app.domain.MaxUiPolicy

/** Explicitly taught system chooser only. Never inspects ordinary third-party apps. */
object MaxSystemPicker {
    data class Snapshot(val picker: MaxRoutePolicy.Picker, val buttons: Map<String, Node>)
    data class Inspection(val report: MaxPickerInspection.Report, val snapshot: Snapshot? = null)
    fun read(context: Context, root: Node): Snapshot? = inspect(context, root).snapshot
    fun inspect(context: Context, root: Node): Inspection {
        fun early(issue: Issue) = Inspection(MaxPickerInspection.Report(issue))
        val pkg = root.packageName?.toString() ?: return early(Issue.NO_PACKAGE)
        if (pkg == MaxUiPolicy.PACKAGE) return early(Issue.MAX_DIRECT)
        if (pkg == context.packageName) return early(Issue.OWN_APP)
        val info = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull()
            ?: return early(Issue.PACKAGE_UNAVAILABLE)
        val flags = info.applicationInfo?.flags ?: return early(Issue.PACKAGE_UNAVAILABLE)
        if (flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) return early(Issue.NOT_SYSTEM)
        val all = mutableListOf<Node>()
        var truncated = false
        fun walk(n: Node, depth: Int) {
            if (all.size >= 250 || depth > 20) { truncated = true; return }
            all += n
            for (i in 0 until n.childCount) {
                val child = n.getChild(i)
                if (child == null) truncated = true else walk(child, depth + 1)
            }
        }
        walk(root, 0)
        if (truncated || all.size >= 250) return early(Issue.TRUNCATED)
        val visible = all.filter { it.isVisibleToUser }
        fun label(n: Node) = MaxRoutePolicy.accessibleLabel(n.text?.toString(), n.contentDescription?.toString())
        val titles = visible.count { MaxRoutePolicy.chooserTitle(label(it)) }
        val labels = visible.filter { !it.isEditable && !it.isPassword && MaxRoutePolicy.candidateLabel(label(it)) }
        fun result(issue: Issue, snapshot: Snapshot? = null) = Inspection(MaxPickerInspection.Report(issue,
            visible.size, titles, labels.size, visible.count { !it.contentDescription.isNullOrBlank() },
            visible.count { it.isEnabled && it.isClickable }), snapshot)
        if (titles == 0) return result(Issue.TITLE_MISSING)
        val texts = labels.map(::label).distinct()
        if (!MaxRoutePolicy.validLabels(texts)) return result(
            if (texts.size > 2) Issue.LABELS_AMBIGUOUS else Issue.LABELS_MISSING)
        fun inside(node: Node, ancestor: Node): Boolean {
            var current: Node? = node
            repeat(24) { if (current == ancestor) return true; current = current?.parent }
            return false
        }
        fun safe(n: Node) = n != root && !n.isEditable && !n.isPassword && !n.isScrollable && n.collectionInfo == null
        val candidates = mutableListOf<Pair<String, Node>>()
        for (labelNode in labels) {
            var container: Node? = labelNode
            var button: Node? = null
            // The icon can be clickable while its sibling caption is not. Require ONE
            // clickable descendant in a small, non-collection container with ONE option.
            for (depth in 0..3) {
                val group = container ?: break
                if (!safe(group) || labels.any { label(it) != label(labelNode) && inside(it, group) }) break
                val members = all.filter { inside(it, group) }
                if (members.size > 18 || members.any { it.isEditable || it.isPassword || it.isScrollable || it.collectionInfo != null }) break
                val clickable = members.filter { it.isVisibleToUser && it.isEnabled && it.isClickable && safe(it) }
                if (clickable.size > 1) return result(Issue.ROW_AMBIGUOUS)
                if (clickable.size == 1) { button = clickable.single(); break }
                container = group.parent
            }
            candidates += label(labelNode) to (button ?: return result(Issue.ROW_UNAVAILABLE))
        }
        val buttons = MaxRoutePolicy.uniqueTargets(candidates) ?: return result(Issue.ROW_AMBIGUOUS)
        val structure = visible.map {
            "${it.className}|${it.viewIdResourceName}|${it.isClickable}|${it.isEditable}|${it.childCount}"
        }.sorted().joinToString("\n")
        val shape = java.security.MessageDigest.getInstance("SHA-256").digest(structure.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        return result(Issue.READY, Snapshot(MaxRoutePolicy.Picker(pkg, info.longVersionCode,
            root.className?.toString().orEmpty(), texts.sorted(), shape), buttons))
    }
}
