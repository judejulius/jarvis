package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** A numbered snapshot of what's on screen. Element ids are only valid until the next snapshot. */
class ScreenSnapshot(
    val text: String,
    val elements: Map<Int, AccessibilityNodeInfo>,
    val foregroundPackage: String?,
)

object ScreenReader {
    private const val MAX_ELEMENTS = 300
    private const val MAX_TEXT = 100

    /** [maxElements] caps how many elements are listed. Small local models get a shorter list to keep prompts short. */
    fun capture(service: AccessibilityService, maxElements: Int = MAX_ELEMENTS): ScreenSnapshot {
        val elements = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val out = StringBuilder()
        var foreground: String? = null

        val windows = runCatching { service.windows }.getOrDefault(emptyList())
            .sortedByDescending { it.layer }
        val roots: List<Pair<String, AccessibilityNodeInfo>> = if (windows.isNotEmpty()) {
            windows.mapNotNull { w ->
                val root = w.root ?: return@mapNotNull null
                if (root.packageName == service.packageName) return@mapNotNull null
                if (w.type == AccessibilityWindowInfo.TYPE_APPLICATION && foreground == null) {
                    foreground = root.packageName?.toString()
                }
                "${windowType(w.type)} window: ${w.title ?: root.packageName}" to root
            }
        } else {
            listOfNotNull(service.rootInActiveWindow?.let { "Active window: ${it.packageName}" to it })
                .also { foreground = it.firstOrNull()?.second?.packageName?.toString() }
        }

        val screen = Rect().also { service.resources.displayMetrics.let { m -> it.set(0, 0, m.widthPixels, m.heightPixels) } }
        out.append("Screen size: ${screen.width()}x${screen.height()}. Foreground app: ${foreground ?: "unknown"}\n")
        for ((label, root) in roots) {
            out.append("-- $label --\n")
            walk(root, 0, elements, out, screen, maxElements)
        }
        if (elements.size >= maxElements) out.append("(more elements not shown; scroll to see more)\n")
        return ScreenSnapshot(out.toString(), elements, foreground)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        elements: MutableMap<Int, AccessibilityNodeInfo>,
        out: StringBuilder,
        screen: Rect,
        maxElements: Int,
    ) {
        if (elements.size >= maxElements || !node.isVisibleToUser) return
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (!Rect.intersects(bounds, screen)) return

        val text = node.text?.toString()?.clean()
        val desc = node.contentDescription?.toString()?.clean()
        val hint = node.hintText?.toString()?.clean()
        val interesting = node.isClickable || node.isLongClickable || node.isEditable || node.isScrollable ||
            node.isCheckable || !text.isNullOrBlank() || !desc.isNullOrBlank()

        var childDepth = depth
        if (interesting) {
            val id = elements.size + 1
            elements[id] = node
            val flags = buildList {
                if (node.isClickable) add("click")
                if (node.isLongClickable) add("longclick")
                if (node.isEditable) add("edit")
                if (node.isScrollable) add("scroll")
                if (node.isCheckable) add(if (node.isChecked) "checked" else "unchecked")
                if (node.isSelected) add("selected")
                if (node.isFocused) add("focused")
                if (!node.isEnabled) add("disabled")
            }
            out.append("  ".repeat(depth.coerceAtMost(8)))
            out.append("[$id] ").append(node.className?.toString()?.substringAfterLast('.') ?: "View")
            if (!text.isNullOrBlank()) out.append(" \"").append(text).append('"')
            if (!desc.isNullOrBlank() && desc != text) out.append(" desc=\"").append(desc).append('"')
            if (!hint.isNullOrBlank() && hint != text) out.append(" hint=\"").append(hint).append('"')
            node.viewIdResourceName?.substringAfter(":id/")?.let { out.append(" id=").append(it) }
            if (flags.isNotEmpty()) out.append(" {").append(flags.joinToString(",")).append('}')
            out.append(" @(").append(bounds.centerX()).append(',').append(bounds.centerY()).append(")\n")
            childDepth = depth + 1
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            walk(child, childDepth, elements, out, screen, maxElements)
        }
    }

    private fun String.clean() = replace('\n', ' ').trim().let { if (it.length > MAX_TEXT) it.take(MAX_TEXT) + "…" else it }

    private fun windowType(type: Int) = when (type) {
        AccessibilityWindowInfo.TYPE_APPLICATION -> "App"
        AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "Keyboard"
        AccessibilityWindowInfo.TYPE_SYSTEM -> "System"
        AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER -> "Divider"
        else -> "Overlay"
    }
}
