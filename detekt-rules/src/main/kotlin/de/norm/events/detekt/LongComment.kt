package de.norm.events.detekt

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiWhiteSpace
import dev.detekt.api.Config
import dev.detekt.api.Configuration
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import dev.detekt.api.config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a comment longer than [maxLines].
 *
 * A comment is prose that has to be maintained like the code under it, so length is a cost. The
 * cap is a smell detector, not a truth: what makes a comment long here is almost always restated
 * code, a history of how the code got this way, or a decision copied out of AGENTS.md or an ADR
 * rather than referenced. See the "Comments and KDoc" section of AGENTS.md.
 *
 * Scraper sub-packages are excluded in `detekt.yml`, because their KDoc is the designated home for
 * accepted limitations and that prose is load-bearing. Anywhere else, a comment that genuinely
 * needs the length is `@Suppress("LongComment")` on the declaration — an explicit decision a
 * reviewer can see.
 */
class LongComment(
    config: Config
) : Rule(config, "Comment is longer than the configured maximum.") {
    @Configuration("maximum number of lines a single comment may span")
    private val maxLines: Int by config(DEFAULT_MAX_LINES)

    override fun visit(root: KtFile) {
        super.visit(root)
        root.commentBlocks().forEach { block ->
            val lines = block.sumOf { it.text.count { char -> char == '\n' } + 1 }
            if (lines > maxLines) {
                report(
                    Finding(
                        Entity.from(block.first()),
                        "This comment spans $lines lines, more than the maximum of $maxLines. Keep the reason, drop the rest."
                    )
                )
            }
        }
    }

    /**
     * Groups the file's comments into blocks: one entry per KDoc or `/* */` comment, and one entry
     * per run of `//` lines that follow each other without a blank line, since such a run reads as
     * a single paragraph.
     */
    private fun KtFile.commentBlocks(): List<List<PsiComment>> {
        val blocks = mutableListOf<MutableList<PsiComment>>()
        collectDescendantsOfType<PsiComment>().forEach { comment ->
            val previous = blocks.lastOrNull()?.last()
            if (previous != null && previous.continuesInto(comment)) {
                blocks.last() += comment
            } else {
                blocks += mutableListOf(comment)
            }
        }
        return blocks
    }

    /** Whether [next] is the line comment directly below this one, with no blank line between. */
    private fun PsiComment.continuesInto(next: PsiComment): Boolean {
        if (tokenType != KtTokens.EOL_COMMENT || next.tokenType != KtTokens.EOL_COMMENT) return false
        var element: PsiElement? = nextSibling
        var blankLine = false
        while (element is PsiWhiteSpace) {
            blankLine = blankLine || element.text.count { it == '\n' } > 1
            element = element.nextSibling
        }
        return element === next && !blankLine
    }

    private companion object {
        const val DEFAULT_MAX_LINES = 25
    }
}
