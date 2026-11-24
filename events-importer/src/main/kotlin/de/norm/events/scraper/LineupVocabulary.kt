package de.norm.events.scraper

/** `Lineup`, `Line-up`, `LINE UP` or `Line - up`, for a pattern that adds its own anchor and colon (#5487). */
const val LINE_UP_WORD = """line\s*-?\s*up"""

/**
 * The names of a list that a `DJs` label has already marked as one: `A, B & C`, `A und B`. Only for such a list, because
 * every join cuts: a bare title can hold a band's `&`. A [knownBillingActs] name stays whole, and so does a name with an
 * unspaced join (`R&B Ralf`).
 */
fun splitDjList(list: String): List<String> =
    (knownBillingActs(list) ?: list.split(',').flatMap { part -> knownBillingActs(part) ?: part.split(DJ_JOIN) })
        .map { it.trim() }
        .filter { it.isNotEmpty() }

private val DJ_JOIN = Regex("""\s+(?:und|and)\s+|\s+[&+]\s*|\s*[&+]\s+""", RegexOption.IGNORE_CASE)

/** Whether [line] closes as a sentence and has [minWords] words; a shorter one can be a name: `arkadiusz.`, `Bleach!`. */
fun endsAsSentence(
    line: String,
    minWords: Int
): Boolean = SENTENCE_END.containsMatchIn(line) && line.split(WHITESPACE).size >= minWords

private val SENTENCE_END = Regex("""[.!?]$""")
