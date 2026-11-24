package de.norm.events.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtStringTemplateExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType

/**
 * Reports a string in a venue's scraper package that spells the line-up label as a pattern of its own: `line[\s-]?up`,
 * `line\s*up`. Twenty-two venues had such a copy, in six spellings, and some missed `Line-up:` or `Line up:` (#5487).
 *
 * Only a spelling with a pattern element between the two words counts, so the text `Line-up` does not.
 */
class PrivateLineUpPattern(
    config: Config
) : Rule(config, "The line-up label has one shared pattern, LINE_UP_WORD.") {
    override fun visit(root: KtFile) {
        super.visit(root)
        if (!VENUE_PACKAGE.matches(root.packageFqName.asString())) return
        root
            .collectDescendantsOfType<KtStringTemplateExpression>()
            .filter { LINE_UP_PATTERN.containsMatchIn(it.text) }
            .forEach { report(Finding(Entity.from(it), "Use `\$LINE_UP_WORD` in place of a private line-up pattern.")) }
    }

    private companion object {
        val VENUE_PACKAGE = Regex("""de\.norm\.events\.scraper\..+""")

        /** What can stand between the two words: `\s`, a character class, a blank or a hyphen, each with a quantifier. */
        const val GAP = """(?:\\{1,2}s|\[[^\]]*]|[ -])[?*+]?"""

        /** The lookahead demands a pattern element; the lookbehind keeps `deadline` out and lets `\bline` in. */
        val LINE_UP_PATTERN = Regex("""(?<!(?<!\\)[a-z])line(?=[ -]*[\\\[]|[ -]+[?*+])(?:$GAP)+up""", RegexOption.IGNORE_CASE)
    }
}
