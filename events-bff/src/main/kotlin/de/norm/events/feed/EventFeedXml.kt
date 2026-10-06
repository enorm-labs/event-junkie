package de.norm.events.feed

import de.norm.events.common.Site
import de.norm.events.common.XmlWriter
import de.norm.events.event.EventFilter
import de.norm.events.event.NewEvent
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renders the event feed as RSS 2.0. RSS rather than Atom, because every reader takes it and
 * `atom:link rel="self"` gives it the one thing it lacks. Item text is data and punctuation, so
 * it needs no translation beyond a date format. An item carries no text and no image of the
 * venue's: the event page shows those, behind the licence gate.
 */
object EventFeedXml {
    const val CONTENT_TYPE = "application/rss+xml"

    /** How long a reader may wait between fetches, in minutes. Imports run daily per source. */
    private const val TTL_MINUTES = 60

    private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"

    private const val SITE_NAME = "Event Junkie"

    private val CHANNEL_TITLES = mapOf("en" to "$SITE_NAME — new events", "de" to "$SITE_NAME — neue Veranstaltungen")
    private val FILTERED_TITLES =
        mapOf("en" to "$SITE_NAME — new events (filtered)", "de" to "$SITE_NAME — neue Veranstaltungen (gefiltert)")

    /**
     * How a title names a type, a venue, or both: `new concerts at Lido`, `neue Konzerte: Lido`. German takes
     * the colon because a preposition would need the venue's gender: "im Columbiahalle" is wrong.
     */
    private class TitleWords(
        val newPlural: String,
        val newAlone: String,
        val beforeVenue: String
    )

    private val TITLE_WORDS = mapOf("en" to TitleWords("new", "new", " at "), "de" to TitleWords("neue", "neu", ": "))
    private val FILTERS_LEAD = mapOf("en" to "Filters", "de" to "Filter")
    private val CHANNEL_DESCRIPTIONS =
        mapOf(
            "en" to "The events in Berlin that Event Junkie found most recently.",
            "de" to "Die Veranstaltungen in Berlin, die Event Junkie zuletzt gefunden hat."
        )
    private val EVENT_DATES =
        mapOf(
            "en" to DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.UK),
            "de" to DateTimeFormatter.ofPattern("EE, d. MMM yyyy", Locale.GERMANY)
        )
    private val EVENT_TIME = DateTimeFormatter.ofPattern("HH:mm")

    /** RFC 822 as RSS reads it, in GMT and in English whatever the feed's locale. */
    private val RFC_822: DateTimeFormatter = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC)

    /**
     * The feed in [locale], one of [Site.LOCALES]. [selfUrl] is absolute, the address a reader subscribed to.
     * [venueName] is the display name of [filter]'s venue, null when there is none or it does not exist.
     */
    fun render(
        locale: String,
        selfUrl: String,
        events: List<NewEvent>,
        filter: EventFilter,
        venueName: String?
    ): String =
        XmlWriter.document("rss", namespaces = mapOf("atom" to ATOM_NAMESPACE), attributes = mapOf("version" to "2.0")) {
            element("channel") {
                element("title", title(locale, filter, venueName))
                element("link", "${Site.URL}/$locale/events")
                element("description", channelDescription(locale, filter, venueName))
                element("language", locale)
                // The newest item is when the feed last gained one; an empty feed has no such moment.
                events.maxOfOrNull { it.firstSeenAt }?.let { element("lastBuildDate", rfc822(it)) }
                element("ttl", TTL_MINUTES.toString())
                emptyElement("atom", ATOM_NAMESPACE, "link", "href" to selfUrl, "rel" to "self", "type" to CONTENT_TYPE)
                events.forEach { item(locale, it) }
            }
        }

    /**
     * Names one event type, one venue, or both (#2765). Anything more is `(filtered)`: a reader truncates
     * a long title, and the description lists every filter. A type or venue that does not exist is too.
     */
    private fun title(
        locale: String,
        filter: EventFilter,
        venueName: String?
    ): String {
        val named = FeedFilterText.nameable(filter)
        val type = named?.eventType?.let { FeedFilterText.eventTypePlural(locale, it) }
        val venue = named?.venueSlug?.let { venueName }
        val words = TITLE_WORDS.getValue(locale)
        return when {
            named == null || (named.eventType != null && type == null) || (named.venueSlug != null && venue == null) -> {
                FILTERED_TITLES.getValue(locale)
            }

            type == null && venue == null -> {
                CHANNEL_TITLES.getValue(locale)
            }

            else -> {
                "$SITE_NAME — " +
                    (type?.let { "${words.newPlural} $it" } ?: words.newAlone) + venue?.let { "${words.beforeVenue}$it" }.orEmpty()
            }
        }
    }

    private fun channelDescription(
        locale: String,
        filter: EventFilter,
        venueName: String?
    ): String {
        val filters = FeedFilterText.describe(locale, filter, venueName)
        val base = CHANNEL_DESCRIPTIONS.getValue(locale)
        return if (filters.isEmpty()) base else "$base ${FILTERS_LEAD.getValue(locale)}: ${filters.joinToString(" · ")}."
    }

    private fun XmlWriter.item(
        locale: String,
        event: NewEvent
    ) {
        val summary = event.summary
        element("item") {
            element("title", summary.title)
            element("link", "${Site.URL}/$locale/events/${summary.slug}")
            // The id, not the URL: an update can change the slug, and a reader would show the event twice.
            element("guid", "tag:event-junkie.de,2026:event:${summary.id}", "isPermaLink" to "false")
            element("pubDate", rfc822(event.firstSeenAt))
            element("description", description(locale, event))
            summary.genreTags.forEach { element("category", it) }
        }
    }

    /** `Sat 12 Jun 2099 · 20:00 · Lido · Artist, Artist`, with the subtitle first when there is one. */
    private fun description(
        locale: String,
        event: NewEvent
    ): String {
        val summary = event.summary
        return listOfNotNull(
            summary.subtitle,
            EVENT_DATES.getValue(locale).format(summary.eventDate),
            (summary.startTime ?: summary.doorsTime)?.format(EVENT_TIME),
            summary.venue.name,
            summary.artistNames.joinToString(", ").ifEmpty { null }
        ).joinToString(" · ")
    }

    private fun rfc822(instant: Instant): String = RFC_822.format(instant)
}
