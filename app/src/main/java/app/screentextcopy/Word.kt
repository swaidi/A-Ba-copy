package app.screentextcopy

import android.graphics.RectF

/**
 * One selectable word on the captured screen.
 * [box] is in screenshot pixels. [line] groups words of the same text line and
 * [para] groups lines of the same paragraph (or chat message / text element).
 * Words inside a line are kept in logical (reading) order, which also handles Arabic.
 */
class Word(
    val text: String,
    val box: RectF,
    val line: Int,
    val para: Int,
    val fromImage: Boolean
) {
    val rtl: Boolean = isRtl(text)

    /** True when this word ends a sentence (Latin and Arabic punctuation). */
    val endsSentence: Boolean = run {
        val t = text.trimEnd('"', '\'', ')', ']', '»', '”', '’', '*')
        t.isNotEmpty() && t.last() in SENTENCE_ENDS
    }

    companion object {
        private val SENTENCE_ENDS = charArrayOf('.', '!', '?', '؟', '…', '۔', '。')

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

        private fun topBucket(list: List<Word>) = (list.minOf { it.box.top } / 12f).toInt()
        private fun leftmost(list: List<Word>) = list.minOf { it.box.left }

        /**
         * Sorts words into reading order, keeping each paragraph together:
         * paragraphs top to bottom, lines top to bottom, words in logical order.
         */
        fun order(words: List<Word>): List<Word> {
            val paras = words.groupBy { it.para }.values
                .sortedWith(compareBy<List<Word>>({ topBucket(it) }, { leftmost(it) }))
            return paras.flatMap { p ->
                p.groupBy { it.line }.values
                    .sortedWith(compareBy<List<Word>>({ topBucket(it) }, { leftmost(it) }))
                    .flatten()
            }
        }

        /**
         * Joins words for copying: wrapped lines of one paragraph become one line,
         * and each paragraph starts on a new line.
         */
        fun join(words: List<Word>): String {
            val sb = StringBuilder()
            var lastPara = Int.MIN_VALUE
            for (w in words) {
                if (sb.isNotEmpty()) sb.append(if (w.para == lastPara) " " else "\n")
                sb.append(w.text)
                lastPara = w.para
            }
            return sb.toString()
        }
    }
}

/** Unique line and paragraph ids shared by both text sources. */
object LineIds {
    private var next = 0

    @Synchronized
    fun newId(): Int = next++
}
