package de.norm.events.event

/**
 * A language a show is performed in, or a screening is subtitled in (#2523). The codes are the
 * closed list the `event_spoken_languages_valid` and `event_subtitle_language_valid` checks accept;
 * a new entry needs a migration first.
 *
 * Not `DescriptionLanguage`, which is the language of the blurb: a German blurb for an English show
 * is common.
 */
enum class SpokenLanguage(
    val code: String
) {
    GERMAN("de"),
    ENGLISH("en"),
    FRENCH("fr"),
    SPANISH("es"),
    ITALIAN("it"),
    TURKISH("tr"),
    RUSSIAN("ru"),
    POLISH("pl")
}
