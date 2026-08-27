package de.norm.events.detekt

import com.intellij.psi.PsiComment
import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset

/**
 * Reports a file that is more comment than code.
 *
 * [LongComment] caps one comment; twenty individually reasonable ones still make a file that reads
 * as prose with code between it, and no per-comment cap sees that. This is the per-file backstop;
 * `scripts/comment-density.sh report` shows the same totals per area but gates nothing (#713).
 *
 * A line carrying both code and a trailing comment counts as code, as `cloc` does, so the figure
 * matches what the script reports. [minCommentLines] keeps a short file with one explanatory
 * paragraph out of it — that ratio is meaningless.
 */
class CommentDensity(
    config: Config
) : Rule(config, "File is more comment than the maximum allows.") {
    @Configuration("maximum percentage of non-blank lines that may be comment")
    private val maxPercent: Int by config(DEFAULT_MAX_PERCENT)

    @Configuration("files with fewer comment lines than this are never reported")
    private val minCommentLines: Int by config(DEFAULT_MIN_COMMENT_LINES)

    override fun visit(root: KtFile) {
        super.visit(root)
        val text = root.text
        val commentLines = root.commentOnlyLines(text).size
        if (commentLines < minCommentLines) return

        val nonBlank = text.lineSequence().count { it.isNotBlank() }
        if (nonBlank == 0) return

        val percent = commentLines * PERCENT / nonBlank
        if (percent > maxPercent) {
            report(
                Finding(
                    Entity.from(root),
                    "This file is $percent% comment, more than the maximum of $maxPercent%. " +
                        "Say it in fewer words, or let a name say it instead."
                )
            )
        }
    }

    /** Line numbers a comment occupies on its own, excluding a line whose comment trails code. */
    private fun KtFile.commentOnlyLines(text: String): Set<Int> {
        val lines = mutableSetOf<Int>()
        collectDescendantsOfType<PsiComment>().forEach { comment ->
            val startLine = text.take(comment.startOffset).count { it == '\n' }
            val lineStart = text.lastIndexOf('\n', comment.startOffset - 1) + 1
            val trailsCode = text.substring(lineStart, comment.startOffset).isNotBlank()
            val first = if (trailsCode) startLine + 1 else startLine
            for (line in first..startLine + comment.text.count { it == '\n' }) lines += line
        }
        return lines
    }

    private companion object {
        const val DEFAULT_MAX_PERCENT = 70
        const val DEFAULT_MIN_COMMENT_LINES = 20
        const val PERCENT = 100
    }
}
