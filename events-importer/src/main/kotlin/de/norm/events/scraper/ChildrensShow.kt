package de.norm.events.scraper

import de.norm.events.event.EventFeature
import de.norm.events.event.EventFeatureMatch

/**
 * The words with which a venue bills a night as a show for small children: `für Kinder`, a `Familien-` show, a `Kinder…`
 * format, or an age range for them (`0-18 Monate`, `ab 4 Jahre`). Null when the night says none of these. EVENT_SCOPE.md
 * §3.5 marks such a night [EventFeature.FOR_CHILDREN] (#3796).
 *
 * A band named after children is no format and stays unmarked (`Muttis Kinder`, `Kinderzimmer Productions`,
 * `Lichterkinder`), and so does a show for teenagers (`ab 13 Jahre`). A venue calls this only where its own page uses
 * these words.
 */
fun childrensShowPhrase(
    title: String,
    subtitle: String?
): String? = CHILDRENS_SHOW.find("$title ${subtitle.orEmpty()}")?.value

/** This event marked [EventFeature.FOR_CHILDREN], with the venue's [phrase] for `event_feature.matched_phrase`. */
fun ScrapedEvent.forChildren(phrase: String): ScrapedEvent =
    if (features.any { it.feature == EventFeature.FOR_CHILDREN }) {
        this
    } else {
        copy(features = features + EventFeatureMatch(EventFeature.FOR_CHILDREN, phrase.trim()))
    }

/** [forChildren] when [phrase] is not null. */
fun ScrapedEvent.forChildrenIf(phrase: String?): ScrapedEvent = phrase?.let(::forChildren) ?: this

private val CHILDRENS_SHOW =
    Regex(
        listOf(
            """f(?:ü|ue)r\s+(?:kinder|kids|die\s+kleinen)\b""",
            """\bfamilien(?:konzert|vorstellung|-\w+|\w*show)""",
            """\bkinder(?:philharmonie|theater|konzert|disco|disko|-\w+)""",
            """\(\s*\d+\s*-\s*\d+\s*(?:monate|jahre)\s*\)""",
            """\bab\s+(?:[1-9]|1[0-2])\s+jahren?\b""",
            """\bkidz\b"""
        ).joinToString("|"),
        RegexOption.IGNORE_CASE
    )
