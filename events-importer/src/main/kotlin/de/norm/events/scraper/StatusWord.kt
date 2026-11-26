package de.norm.events.scraper

/**
 * Whether [name] is a status word, not an act: the whole name is the cancel, move or sold-out vocabulary the title
 * parsers read ([TITLE_STATUS_PATTERN], [SOLD_OUT_WORD]) or the notices' `wird`. Uber Arena's `Wird verschoben: …`
 * billed `WIRD`, which MusicBrainz matched EXACT to a London band (#5569). A name with any other word is an act:
 * `Wird verschoben: Deep Purple` names Deep Purple.
 */
fun isStatusWord(name: String): Boolean {
    val text = name.lowercase()
    if (text.none { it.isLetter() }) return false
    return STATUS_VOCABULARY.fold(text) { rest, word -> rest.replace(word, " ") }.none { it.isLetterOrDigit() }
}

/** The auxiliary of a notice: `Wird verschoben`, `wird verlegt`. Alone it names nothing, so it is no act. */
private val NOTICE_AUXILIARY = Regex("""(?<!\p{L})wird(?!\p{L})""")

private val STATUS_VOCABULARY = listOf(TITLE_STATUS_PATTERN, Regex(SOLD_OUT_WORD, RegexOption.IGNORE_CASE), NOTICE_AUXILIARY)
