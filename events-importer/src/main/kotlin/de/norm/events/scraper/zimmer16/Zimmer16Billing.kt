package de.norm.events.scraper.zimmer16

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.isNonArtistName

/**
 * The act of a Zimmer 16 concert [title], billed only when the event page's [description] repeats its name (#4829).
 *
 * A title here is a programme name and an act, joined by `, `, ` – `, ` - `, `: `, `? ` or nothing, so its shape
 * does not say which part is the act. The whole title is billed unsplit when the text repeats it
 * (`between the trees and me`, `Stolzmann & friends`). Otherwise the title splits into sides. A side offers its
 * co-billed acts when the text repeats each (`Lea Willms and MUD/O`), else itself, else its longest leading or
 * trailing run of capitalised words (`Tobias Panwitz Folksong-Reisen`, `… Kompromisse Julia Alsheimer`). A run
 * beside an `und` is half a co-bill and counts for nothing. The act is the candidate the text repeats verbatim, not
 * all capitals, not led by a stop word and not ending in a format word (`Spook Show`). Exactly one side must offer
 * one, or nobody is billed. A one-word side counts only before a dash (`saxyarpa – …`), never after a comma
 * (`…, Landmusik`). A hommage, an open stage and a `<record> Release Tour` name nobody who plays.
 */
internal fun billedFromPage(
    title: String,
    description: String?
): List<ScrapedArtist> {
    if (description.isNullOrBlank() || NOBODY_BILLED.containsMatchIn(title)) return emptyList()
    val whole = title.replace(TRAILING_SOLO, "").trim()
    val names =
        if (isNamed(whole) && repeats(description, whole)) {
            listOf(whole)
        } else {
            sidesOf(whole).mapNotNull { side -> actsOfSide(side, description) }.singleOrNull().orEmpty()
        }
    return names.filterNot(::isNonArtistName).map { ScrapedArtist(name = it) }
}

/** A side of a title, and whether the separator after it is a dash. */
private data class Side(
    val text: String,
    val first: Boolean,
    val beforeDash: Boolean
)

private fun sidesOf(title: String): List<Side> {
    val parts = SEPARATOR.split(title)
    val separators = SEPARATOR.findAll(title).map { it.value.trim() }.toList()
    return parts.mapIndexedNotNull { index, part ->
        val text = part.replace(TRAILING_SOLO, "").trim { it in EDGE_PUNCTUATION }
        Side(text, index == 0, separators.getOrNull(index) in DASHES).takeUnless { text.isBlank() || RELEASE_TOUR.containsMatchIn(text) }
    }
}

/** The act or co-billed acts [side] names, or null when the text repeats none of its candidates. */
private fun actsOfSide(
    side: Side,
    description: String
): List<String>? {
    val words = side.text.split(WHITESPACE)
    if (words.size == 1) {
        return listOf(side.text).takeIf { side.first && side.beforeDash && isActShaped(side.text) && repeats(description, side.text) }
    }
    coBilled(words, description)?.let { return it }
    val runs =
        (words.size - 1 downTo 2).flatMap { length ->
            listOf(
                words.take(length).takeUnless { words[length].lowercase() in CO_BILL_WORDS },
                words.takeLast(length).takeUnless { words[words.size - length - 1].lowercase() in CO_BILL_WORDS }
            ).filterNotNull().filter { run -> run.all { it.first().isUpperCase() } }
        }
    return (listOf(side.text) + runs.map { run -> run.joinToString(" ").trim { it in EDGE_PUNCTUATION } })
        .firstOrNull { isActShaped(it) && repeats(description, it) }
        ?.let(::listOf)
}

/** `<act> und <act>`, `&`, `and` or `+`, when the text repeats every act: `Lea Willms and MUD/O`. */
private fun coBilled(
    words: List<String>,
    description: String
): List<String>? {
    if (words.none { it.lowercase() in CO_BILL_WORDS }) return null
    val parts = words.joinToString(" ").split(CO_BILL).map(String::trim)
    return parts.takeIf { names ->
        names.all { it.firstOrNull()?.isUpperCase() == true && !isStopWordLed(it) && !endsInFormatWord(it) && repeats(description, it) }
    }
}

private fun isActShaped(name: String): Boolean = name.split(WHITESPACE).none { it.lowercase() in CO_BILL_WORDS } && isNamed(name)

/** Not all capitals, not led by a stop word and not ending in a format word. */
private fun isNamed(name: String): Boolean = !isAllCaps(name) && !isStopWordLed(name) && !endsInFormatWord(name)

private fun endsInFormatWord(name: String): Boolean = name.substringAfterLast(' ').lowercase() in FORMAT_WORDS

private fun isAllCaps(name: String): Boolean = name.none { it.isLowerCase() }

private fun isStopWordLed(name: String): Boolean = name.substringBefore(' ').lowercase() in STOP_WORDS

private fun repeats(
    description: String,
    name: String
): Boolean = name.isNotBlank() && Regex("""(?<![\p{L}\p{N}])${Regex.escape(name)}(?![\p{L}\p{N}])""").containsMatchIn(description)

private val SEPARATOR = Regex(""", | [–—-] |: |\? """)
private val DASHES = setOf("–", "—", "-")
private val TRAILING_SOLO = Regex("""\s+Solo$""")
private val RELEASE_TOUR = Regex("""\sRelease[- ]Tour$""", RegexOption.IGNORE_CASE)

/** A hommage names whom it honours, and an open stage anyone who comes: neither names the performer. */
private val NOBODY_BILLED = Regex("""\b(?:Hommage|Homage|Tribute|Tribut)\b|offene \p{L}*bühne""", RegexOption.IGNORE_CASE)
private val EDGE_PUNCTUATION = setOf(' ', ',', ':', ';', '!', '?', '"', '„', '“', '”', '‚', '‘', '’', '\'')
private val CO_BILL_WORDS = setOf("und", "and", "&", "+")
private val CO_BILL = Regex(""" (?:und|and|&|\+) """)

/** The last word of a format, not an act: `Spook Show`, `Doppelkonzert`. */
private val FORMAT_WORDS = setOf("show", "revue", "konzert", "doppelkonzert", "lesung", "tour", "abend", "programm")
private val STOP_WORDS =
    setOf(
        "der",
        "die",
        "das",
        "den",
        "dem",
        "des",
        "ein",
        "eine",
        "einer",
        "einem",
        "einen",
        "eines",
        "mit",
        "im",
        "in",
        "am",
        "an",
        "auf",
        "aus",
        "bei",
        "vom",
        "von",
        "zum",
        "zur",
        "zu",
        "für",
        "über",
        "so",
        "wie",
        "was",
        "wer",
        "ja",
        "nein",
        "nicht",
        "noch",
        "mal",
        "ganz",
        "immer"
    )
