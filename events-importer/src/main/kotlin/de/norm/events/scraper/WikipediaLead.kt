package de.norm.events.scraper

import de.norm.events.wikimedia.WikipediaExtract

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

    private const val BIRTH_DATA_REFUSAL = "birth-data"

    private val GERMAN_SPEAKING = setOf("DE", "AT", "CH")

    /**
     * The marks of a person's dates in either wiki's lead: `(* 6. September 1986 in Göteborg)`, `born`, `geboren`, `†`.
     * `geboren` is case-sensitive: German writes the participle in lower case mid-sentence, and a capital one is a title (#1879).
     */
    private val BIRTH_DATA =
        listOf(
            Regex("\\(\\s*\\*"),
            Regex("\\bgeb(oren|\\.)"),
            Regex("(^|[.!?]\\s+)Geboren\\s+(am\\b|in\\b|\\d)"),
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
            BIRTH_DATA.any { it.containsMatchIn(text) } || DATED_LEAD.containsMatchIn(text.substringBefore(". ")) -> BIRTH_DATA_REFUSAL
            text.length < MIN_LENGTH -> "short"
            else -> null
        }

    /** The leads to store as the description and its other-language text, and the refusal of each lead left out. */
    data class Choice(
        val primary: WikipediaExtract?,
        val alt: WikipediaExtract?,
        val refusals: List<String>
    )

    /**
     * Which of [extracts], in preference order, to store. With [storedLanguage] null the row has no
     * description: the first lead that passes becomes it, and the next passing one in the other
     * language its alt. With [storedLanguage] set only the alt is open. A short lead refuses itself
     * only. Birth data refuses every lead: it marks the item as a person, and the other wiki's article
     * is about the same person, where the guard may miss what it caught here.
     */
    fun choose(
        extracts: List<WikipediaExtract>,
        storedLanguage: String? = null
    ): Choice {
        val refusals = extracts.associateWith { refusalOf(it.text) }
        if (BIRTH_DATA_REFUSAL in refusals.values) return Choice(null, null, extracts.map { BIRTH_DATA_REFUSAL })
        val passing = extracts.filter { refusals[it] == null }
        val primary = if (storedLanguage == null) passing.firstOrNull() else null
        val primaryLanguage = storedLanguage ?: primary?.language
        val alt = primaryLanguage?.let { language -> passing.firstOrNull { it.language != language } }
        return Choice(primary, alt, refusals.values.filterNotNull())
    }
}
