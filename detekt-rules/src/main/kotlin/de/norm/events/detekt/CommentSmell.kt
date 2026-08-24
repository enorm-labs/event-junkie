package de.norm.events.detekt

import com.intellij.psi.PsiComment
import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports the things AGENTS.md §Comments already forbids, now that a build can see them: a date, a
 * markdown heading, a comment narrating its own history, and a `TODO` (#713).
 *
 * Each was policy and nothing failed on it, which is why the tree accumulated them.
 *
 * **A date inside backticks or quotes is data, not a changelog**, and is left alone — the tree
 * documents its parsers with `"2026-05-16T20:00"` far more often than it dates a decision, and a
 * rule that cannot tell the two apart gets switched off. Fenced blocks are skipped for the same
 * reason. **Only past-tense narration counts as history**: "used to" is overwhelmingly the verb
 * ("used to resolve the links"), so a subject pronoun has to precede it.
 */
class CommentSmell(
    config: Config
) : Rule(config, "Comment holds something git, the issue or a name already records.") {
    override fun visit(root: KtFile) {
        super.visit(root)
        root.collectDescendantsOfType<PsiComment>().forEach { comment ->
            var inFence = false
            val smells = linkedSetOf<String>()
            comment.text.lineSequence().forEach { raw ->
                val line =
                    raw
                        .trim()
                        .removePrefix("/**")
                        .removePrefix("/*")
                        .removePrefix("//")
                        .removeSuffix("*/")
                        .trim()
                        .removePrefix("*")
                        .trim()
                if (line.startsWith("```")) {
                    inFence = !inFence
                    return@forEach
                }
                if (inFence || line.isEmpty()) return@forEach
                smellOf(line)?.let { smells += it }
            }
            // One finding per smell per comment: three dates in one paragraph are one thing to fix.
            smells.forEach { report(Finding(Entity.from(comment), it)) }
        }
    }

    private fun smellOf(line: String): String? {
        if (HEADING.containsMatchIn(line)) return HEADING_MESSAGE
        // Everything below reads prose only: a marker inside backticks or quotes is being named, not used.
        val prose = line.replace(CODE_SPAN, " ").replace(QUOTED, " ")
        return PROSE_SMELLS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(prose) }?.second
    }

    private companion object {
        const val HEADING_MESSAGE =
            "A markdown heading in a comment means the content is a document in the wrong file. " +
                "Put it in docs/, an ADR or the issue, and leave a pointer."

        val HEADING = Regex("""^#{2,6}\s""")
        val CODE_SPAN = Regex("""`[^`]*`""")
        val QUOTED = Regex(""""[^"]*"""")

        val PROSE_SMELLS =
            listOf(
                Regex("""\b(TODO|FIXME)\b""") to
                    "A TODO rots in silence. File it as an issue and reference the number.",
                Regex("""\d{4}-\d{2}-\d{2}""") to
                    "A date belongs in git blame, the PR or the issue, not in a comment describing the code as it stands.",
                Regex(
                    """\b(it|this|that|they|we|which|these|those)\s+used\s+to\b|\bpreviously[,.]|\bas of \d|\bnowadays\b""",
                    RegexOption.IGNORE_CASE
                ) to
                    "This comment narrates a change rather than describing the code as it stands. Rewrite it in the present tense."
            )
    }
}
