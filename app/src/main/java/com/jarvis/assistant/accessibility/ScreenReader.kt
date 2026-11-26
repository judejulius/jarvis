package com.jarvis.assistant.accessibility

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.text.InputType
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/** A numbered snapshot of what's on screen. Element ids are only valid until the next snapshot. */
class ScreenSnapshot(
    val text: String,
    val elements: Map<Int, AccessibilityNodeInfo>,
    val foregroundPackage: String?,
    val windowId: Int? = null,
)

object ScreenReader {
    private const val MAX_ELEMENTS = 300
    private const val MAX_TEXT = 100

    /** [maxElements] caps how many elements are listed. Small local models get a shorter list to keep prompts short. */
    fun capture(service: AccessibilityService, maxElements: Int = MAX_ELEMENTS): ScreenSnapshot {
        val elements = LinkedHashMap<Int, AccessibilityNodeInfo>()
        val out = StringBuilder()
        val window = service.windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: return ScreenSnapshot("No active application window.", elements, null)
        val root = window.root ?: return ScreenSnapshot("Window unavailable.", elements, null)
        val foreground = root.packageName?.toString()
        if (foreground == null || foreground == service.packageName || containsSensitiveContent(root)) {
            return ScreenSnapshot("This window is private.", elements, null)
        }

        val screen = Rect().also { service.resources.displayMetrics.let { m -> it.set(0, 0, m.widthPixels, m.heightPixels) } }
        out.append("Screen size: ${screen.width()}x${screen.height()}. Foreground app: ${foreground ?: "unknown"}\n")
        walk(root, 0, elements, out, screen, maxElements)
        if (elements.size >= maxElements) out.append("(more elements not shown; scroll to see more)\n")
        return ScreenSnapshot(out.toString(), elements, foreground, window.id)
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        elements: MutableMap<Int, AccessibilityNodeInfo>,
        out: StringBuilder,
        screen: Rect,
        maxElements: Int,
    ) {
        if (depth >= 60 || elements.size >= maxElements || !node.isVisibleToUser || sensitive(node)) return
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

    private fun sensitive(node: AccessibilityNodeInfo): Boolean {
        val type = node.inputType
        val variation = type and InputType.TYPE_MASK_VARIATION
        val passwordInput = when (type and InputType.TYPE_MASK_CLASS) {
            InputType.TYPE_CLASS_TEXT -> variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD)
            InputType.TYPE_CLASS_NUMBER -> variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            else -> false
        }
        return node.isPassword || passwordInput || (Build.VERSION.SDK_INT >= 34 && node.isAccessibilityDataSensitive)
    }

    // A parent may repeat a child's text. Withhold the window if inspection is incomplete.
    internal fun containsSensitiveContent(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.add(root)
        var visited = 0
        while (pending.isNotEmpty()) {
            val node = pending.removeLast()
            if (++visited > 1600 || sensitive(node) || node.childCount > 1600 - pending.size) return true
            for (i in 0 until node.childCount) pending.add(node.getChild(i) ?: return true)
        }
        return false
    }
}
