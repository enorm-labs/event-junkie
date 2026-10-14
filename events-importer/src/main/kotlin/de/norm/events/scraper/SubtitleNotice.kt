package de.norm.events.scraper

/** The words of a sold-out, postponed, relocated or cancelled banner; `VERLEGT` also matches `HOCHVERLEGT`. */
private val NOTICE_WORD = Regex("AUSVERKAUFT|VERSCHOBEN|VERLEGT|ABGESAGT")

/**
 * Splits Astra's and Lido's subtitle lines into the subtitle and the shouted banner below it, each `null` when empty.
 *
 * Joined as one text, a banner such as `VERLEGT INS LIDO. …` read as the support act "Gym Tonic Verlegt Ins Lido" (see #1138).
 * A line is a notice when it is shouted and follows a blank line or carries a [NOTICE_WORD]; see #3282.
 * A lower-case lineup after the blank and a shouted party name such as `HEAVY ROCK DISCOTHEQUE` stay subtitle.
 * Read [lines] with `textLines(keepBlankLines = true)`, so the blank line survives.
 */
internal fun splitSubtitleNotice(lines: List<String>): Pair<String?, String?> {
    val firstBlank = lines.indexOfFirst { it.isBlank() }.takeIf { it >= 0 } ?: lines.size
    val (notice, subtitle) =
        lines
            .withIndex()
            .filter { it.value.isNotBlank() }
            .partition { (index, line) -> line.isShouted() && (index > firstBlank || NOTICE_WORD.containsMatchIn(line)) }
    return subtitle.joinToString(" ") { it.value }.ifBlank { null } to notice.joinToString(" ") { it.value }.ifBlank { null }
}

/** True for a capitals-only line — the venue's style for a notice, never a tour name or act. */
private fun String.isShouted(): Boolean = any { it.isLetter() } && none { it.isLowerCase() }
