package de.norm.events.scraper

import de.norm.events.event.EventType
import de.norm.events.event.SpokenLanguage

// The spoken language of a show, read from what the venue wrote (#2523). Explicit phrases only:
// a wrong language sends a visitor to a show they cannot follow, so an unsure text yields null.

/** What a show is performed in, and what a screening is subtitled in. Null where the text says nothing. */
data class SpokenLanguages(
    val spoken: List<String>? = null,
    val subtitle: String? = null
) {
    companion object {
        val UNKNOWN = SpokenLanguages()
    }
}

/** The event types a visitor chooses by language. A concert's lyrics are not a reason to go or stay away. */
private val LANGUAGE_TYPES = setOf(EventType.COMEDY, EventType.READING, EventType.SCREENING, EventType.SHOW, EventType.OTHER)

private fun phrase(pattern: String) = Regex(pattern, RegexOption.IGNORE_CASE)

/** Subtitle markers. They are cut from the text first, so "subtitles in English" is not an English show. */
private val SUBTITLE_MARKERS: List<Pair<Regex, SpokenLanguage>> =
    listOf(
        // Case-sensitive: the cinema abbreviations are always written this way, and "omu" is a word elsewhere.
        Regex("""\bOm(?:e|en|engl)U\b""") to SpokenLanguage.ENGLISH,
        Regex("""\bOm(?:d|dt)?U\b""") to SpokenLanguage.GERMAN,
        phrase("""\b(?:with\s+)?english\s+subtitles?\b|\bsubtitles?\s+in\s+english\b|\benglischen?\s+untertitel\w*""") to SpokenLanguage.ENGLISH,
        phrase("""\b(?:with\s+)?german\s+subtitles?\b|\bsubtitles?\s+in\s+german\b|\bdeutschen?\s+untertitel\w*""") to SpokenLanguage.GERMAN
    )

/**
 * "englischsprachig" alone is not a marker: "der englischsprachigen Szene" describes a scene, not
 * the show. Only "englischsprachige Show" and its kin say what is spoken on stage.
 */
private const val SHOW_NOUN = """(?:show|comedy(?![\w-])|stand[\s-]?up|programm|abend|lesung|vorstellung|improv\w*|theater(?![\w-]))"""

private val ENGLISH_MARKER =
    phrase(
        """\bin\s+english\b|\bauf\s+englisch\b|\benglischsprachige[mnrs]?\s+$SHOW_NOUN|\benglish[\s-]+language\b|\bin\s+englischer\s+sprache\b""" +
            """|\benglish(?:[\s-]+speaking)?[\s-]+$SHOW_NOUN|\b(?:sprache|language):\s*(?:englisch|english)\b"""
    )

private val GERMAN_MARKER =
    phrase(
        """\bin\s+german\b|\bauf\s+deutsch\b|\bdeutschsprachige[mnrs]?\s+$SHOW_NOUN|\bgerman[\s-]+language\b|\bin\s+deutscher\s+sprache\b""" +
            """|\b(?:sprache|language):\s*(?:deutsch|german)\b"""
    )

/** Both at once: "zweisprachig", "Deutsch & Englisch", "In English, German and without words". */
private val BILINGUAL_MARKER =
    phrase(
        """\bbilingual\b|\bzweisprachig\w*|\b(?:deutsch|german)\s*(?:,|/|&|\+|und|and)\s*(?:englisch|english)\b""" +
            """|\b(?:englisch|english)\s*(?:,|/|&|\+|und|and)\s*(?:deutsch|german)\b"""
    )

/**
 * The languages [title], [subtitle] and [description] state, for an event of [type]. The title
 * and subtitle win over the description, which may quote another show. Never inferred from the
 * language the text is written in: a German blurb for an English show is common.
 */
fun detectSpokenLanguages(
    title: String,
    subtitle: String?,
    description: String?,
    type: EventType
): SpokenLanguages {
    if (type !in LANGUAGE_TYPES) return SpokenLanguages.UNKNOWN
    val heading = listOfNotNull(title, subtitle).joinToString("\n")
    val fromHeading = readLanguages(heading)
    val fromDescription = description?.let(::readLanguages) ?: SpokenLanguages.UNKNOWN
    return SpokenLanguages(
        spoken = fromHeading.spoken ?: fromDescription.spoken,
        subtitle = fromHeading.subtitle ?: fromDescription.subtitle
    )
}

private fun readLanguages(text: String): SpokenLanguages {
    val subtitle = SUBTITLE_MARKERS.firstOrNull { (marker, _) -> marker.containsMatchIn(text) }?.second
    val spokenText = SUBTITLE_MARKERS.fold(text) { rest, (marker, _) -> marker.replace(rest, " ") }
    val found =
        when {
            BILINGUAL_MARKER.containsMatchIn(spokenText) -> {
                setOf(SpokenLanguage.GERMAN, SpokenLanguage.ENGLISH)
            }

            else -> {
                setOfNotNull(
                    SpokenLanguage.GERMAN.takeIf { GERMAN_MARKER.containsMatchIn(spokenText) },
                    SpokenLanguage.ENGLISH.takeIf { ENGLISH_MARKER.containsMatchIn(spokenText) }
                )
            }
        }
    return SpokenLanguages(
        spoken = found.sortedBy { it.ordinal }.map { it.code }.ifEmpty { null },
        subtitle = subtitle?.code
    )
}
