package de.norm.events.scraper

/**
 * When an ensemble's Wikipedia lead may become its description (ADR-031, step C+, see #1569).
 *
 * MusicBrainz types some solo acts as groups, so a lead that reads like a person's birth data is
 * refused on an ensemble too. A false refusal costs one description; a false pass breaks §4 of the
 * privacy notice.
 */
object WikipediaLead {
    /** Wikipedia's text licence on both wikis; the credit names the site and links the article. */
    const val ATTRIBUTION = "Wikipedia"
    const val LICENCE_ID = "CC-BY-SA-4.0"

    /** Below this a lead is a genre and a city, which the page already says, under a licence line it must carry. */
    private const val MIN_LENGTH = 80

    private val GERMAN_SPEAKING = setOf("DE", "AT", "CH")

    /** The marks of a person's dates in either wiki's lead: `(* 6. September 1986 in Göteborg)`, `born`, `geboren`, `†`. */
    private val BIRTH_DATA =
        listOf(
            Regex("\\(\\s*\\*"),
            Regex("\\bgeb(oren|\\.)", RegexOption.IGNORE_CASE),
            Regex("\\bborn\\b", RegexOption.IGNORE_CASE),
            Regex("†")
        )

    /** A year in brackets in the first sentence is how both wikis open a person's article; an ensemble's lead rarely does. */
    private val DATED_LEAD = Regex("\\([^)]*\\b\\d{4}\\b[^)]*\\)")

    /** The wikis to read, preferred first: German for an act from a German-speaking country, English otherwise. */
    fun languagesFor(country: String?): List<String> = if (country?.uppercase() in GERMAN_SPEAKING) listOf("de", "en") else listOf("en", "de")

    /** `birth-data` or `short` when [text] may not be stored, null when it may. */
    fun refusalOf(text: String): String? =
        when {
            BIRTH_DATA.any { it.containsMatchIn(text) } || DATED_LEAD.containsMatchIn(text.substringBefore(". ")) -> "birth-data"
            text.length < MIN_LENGTH -> "short"
            else -> null
        }
}
