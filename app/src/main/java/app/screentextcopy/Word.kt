package app.screentextcopy

import android.graphics.RectF

/**
 * One selectable word on the captured screen.
 * [box] is in screenshot pixels. [line] groups words of the same text line;
 * words inside a line are kept in logical (reading) order, which also handles Arabic.
 */
class Word(
    val text: String,
    val box: RectF,
    val line: Int,
    val fromImage: Boolean
) {
    val rtl: Boolean = isRtl(text)

    companion object {
        fun isRtl(text: String): Boolean {
            for (ch in text) {
                when (Character.getDirectionality(ch)) {
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT,
                    Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC -> return true
                    Character.DIRECTIONALITY_LEFT_TO_RIGHT -> return false
                }
            }
            return false
        }

        /** Sorts words into reading order: lines top to bottom, words in logical order. */
        fun order(words: List<Word>): List<Word> {
            val byLine = words.groupBy { it.line }
            val lines = byLine.values.sortedWith(
                compareBy<List<Word>>(
                    { line -> (line.minOf { it.box.top } / 12f).toInt() },
                    { line -> line.minOf { it.box.left } }
                )
            )
            return lines.flatten()
        }

        /** Joins words: spaces inside a line, a line break between lines. */
        fun join(words: List<Word>): String {
            val sb = StringBuilder()
            var lastLine = Int.MIN_VALUE
            for (w in words) {
                if (sb.isNotEmpty()) sb.append(if (w.line == lastLine) " " else "\n")
                sb.append(w.text)
                lastLine = w.line
            }
            return sb.toString()
        }
    }
}

/** Unique line ids shared by both text sources. */
object LineIds {
    private var next = 0

    @Synchronized
    fun newId(): Int = next++
}
