package app.screentextcopy

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.core.os.BundleCompat
import kotlin.math.abs
import kotlin.math.min

/**
 * Reads the text that apps expose to accessibility (chats, web pages, documents),
 * with the on-screen position of every character, so it is exact and needs no OCR.
 * Text inside images is not available here; the OCR engine handles that.
 */
object DirectText {
    private const val MAX_NODES = 4000
    private const val MAX_CHARS_PER_NODE = 3000

    fun collect(service: AccessibilityService, screenW: Int, screenH: Int): List<Word> {
        val out = ArrayList<Word>()
        val screen = Rect(0, 0, screenW, screenH)
        val roots = ArrayList<AccessibilityNodeInfo>()
        try {
            for (window in service.windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
                window.root?.let { roots.add(it) }
            }
        } catch (_: Exception) {
        }
        if (roots.isEmpty()) service.rootInActiveWindow?.let { roots.add(it) }

        var visited = 0
        for (root in roots) {
            if (root.packageName?.toString() == service.packageName) continue
            val stack = ArrayDeque<AccessibilityNodeInfo>()
            stack.addLast(root)
            while (stack.isNotEmpty() && visited < MAX_NODES) {
                val node = stack.removeLast()
                visited++
                if (!node.isVisibleToUser) continue
                val text = node.text?.toString()
                if (!text.isNullOrBlank() && !node.isPassword) {
                    try {
                        addNodeWords(node, text, screen, out)
                    } catch (_: Exception) {
                    }
                }
                for (i in node.childCount - 1 downTo 0) {
                    node.getChild(i)?.let { stack.addLast(it) }
                }
            }
        }
        return out
    }

    private fun addNodeWords(node: AccessibilityNodeInfo, text: String, screen: Rect, out: MutableList<Word>) {
        val nodeBounds = Rect()
        node.getBoundsInScreen(nodeBounds)
        if (!nodeBounds.intersect(screen) || nodeBounds.isEmpty) return

        val charBoxes = characterBoxes(node, text)
        if (charBoxes == null) {
            // No per-character positions: offer the whole element as one selectable block.
            val clean = text.trim()
            if (clean.length <= 600) {
                val id = LineIds.newId()
                out.add(Word(clean, RectF(nodeBounds), id, id, fromImage = false))
            }
            return
        }

        val visible = RectF(nodeBounds)
        // Each text element starts a paragraph; a line break inside its text starts another.
        var para = LineIds.newId()
        var line = LineIds.newId()
        var prev: RectF? = null
        var newlineSincePrev = false
        var index = 0
        val regex = Regex("\\S+")
        for (match in regex.findAll(text)) {
            if (text.substring(index, match.range.first).contains('\n')) newlineSincePrev = true
            index = match.range.last + 1

            val box = RectF()
            var any = false
            for (i in match.range) {
                val r = charBoxes.getOrNull(i) ?: continue
                if (r.isEmpty) continue
                if (!any) box.set(r) else box.union(r)
                any = true
            }
            if (!any || !RectF.intersects(box, visible)) {
                prev = null
                continue
            }
            box.intersect(visible)

            val p = prev
            if (p != null) {
                val sameRow = abs(p.centerY() - box.centerY()) < 0.6f * min(p.height(), box.height())
                if (newlineSincePrev) {
                    para = LineIds.newId()
                    line = LineIds.newId()
                } else if (!sameRow) {
                    line = LineIds.newId()
                }
            }
            newlineSincePrev = false
            out.add(Word(match.value, box, line, para, fromImage = false))
            prev = box
        }
    }

    /** Asks the app for character positions; returns null if the app does not provide them. */
    private fun characterBoxes(node: AccessibilityNodeInfo, text: String): List<RectF?>? {
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (!node.availableExtraData.contains(key)) return null
        val length = min(text.length, MAX_CHARS_PER_NODE)
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, length)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        val array = BundleCompat.getParcelableArray(node.extras, key, RectF::class.java) ?: return null
        if (array.none { it != null }) return null
        return array.map { it as? RectF }
    }
}
