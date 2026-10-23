package de.norm.events.scraper.eightmm

import de.norm.events.common.BERLIN
import de.norm.events.common.deshout
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.dropPastEvents
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Parser for the `<noscript>` list under 8MM's Squarespace calendar on `/program`: one `li` per event of the month
 * shown, with a title link, a poster and a date line (`Saturday, October 17, 2026, 7:00 PM – 8:00 PM`).
 *
 * - **The end time is a placeholder**: every event ends one hour after it starts, so it is not read.
 * - **The residency repeats one slug on each date**, so the `sourceId` keeps the `/program/<y>/<m>/<d>/` path.
 * - **A `LIVE` title is a concert, a `DJ` title a party**; the bands come from a concert's title only.
 * - **Only a title in the house frame bills its acts**: `8MM Live: <acts>`, `8MM + <promoter> pres: <acts>` or
 *   `<acts> LIVE in 8MM`. Any other title names the night, as `KOROWA — A Night of Live and Dark Sounds` or
 *   `8MM X UNKNOWN CALLERS HALLOWEEN`, and bills nobody. The acts of such a night are only in the blurb's prose.
 *   The house's own `8MM` is never an act.
 * - **A `<name> (<act>) … DJ RESIDENCY` title bills its DJ**; the bracketed band names where the DJ comes from and does not play.
 *   Every other DJ title is free-form and bills nobody.
 */
class EightMmProgramPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("noscript li:has(> h1 > a[href*=/program/])")
        logger.info { "Found ${items.size} event(s) on the 8MM programme" }
        return items
            .mapSkippingFailures(logger, "8MM event item") { parseItem(it, sourceUrl) }
            .dropPastEvents(clock) { dropped -> logger.info { "Dropped $dropped past event(s) from the 8MM programme" } }
    }

    private fun parseItem(
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst("> h1 > a[href]") ?: error("No event link found")
        val title = cleanEventTitle(link.text())
        if (title.isBlank()) {
            logger.warn { "8MM event on $sourceUrl has no title, skipping" }
            return null
        }
        val dateLine = item.select("> div").firstNotNullOfOrNull { DATE_LINE.find(it.text()) }
        val eventDate = dateLine?.groups["date"]?.let { parseDate(it.value) }
        if (dateLine == null || eventDate == null) {
            logger.warn { "No parseable date for 8MM event '$title', skipping" }
            return null
        }
        val url = link.absUrl("href").ifEmpty { error("No absolute event URL for '$title' on $sourceUrl") }
        val eventType = eventTypeOf(title)
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            startTime = parseClock(dateLine.groups["clock"]?.value, dateLine.groups["meridiem"]?.value),
            imageUrl = item.attrAt("img[data-src]", "data-src")?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${EventSource.EIGHT_MM.sourceIdPrefix}${extractEventSlug(url, "/program/").replace('/', '-')}",
            artists = residencyDj(title, eventType)?.let { listOf(it) } ?: concertBill(title, eventType)
        )
    }

    private fun parseDate(text: String): LocalDate? =
        try {
            LocalDate.parse(text, DATE_FORMAT)
        } catch (_: DateTimeParseException) {
            null
        }

    private fun eventTypeOf(title: String): String =
        when {
            LIVE.containsMatchIn(title) -> EventType.CONCERT.name
            DJ.containsMatchIn(title) -> EventType.PARTY.name
            else -> inferConcertVenueType(title)
        }

    /** The acts of `8MM Live: SOFT PALMS (US)` or `RMFC LIVE in 8MM`, without the house's frame; none outside the frame. */
    private fun concertBill(
        title: String,
        eventType: String
    ): List<ScrapedArtist> {
        if (!HOUSE_PREFIX.containsMatchIn(title) && !HOUSE_SUFFIX.containsMatchIn(title)) return emptyList()
        val acts = title.replace(HOUSE_PREFIX, "").replace(HOUSE_SUFFIX, "").trim()
        return buildArtistsForEventType(acts, null, eventType).filterNot { it.name.equals(HOUSE, ignoreCase = true) }
    }

    /** `JEFF CLARK (BLACK LIPS) SUNDAY'S IN OCTOBER DJ RESIDENCY` bills `Jeff Clark`. */
    private fun residencyDj(
        title: String,
        eventType: String
    ): ScrapedArtist? =
        RESIDENCY
            .takeIf { eventType == EventType.PARTY.name }
            ?.find(title)
            ?.groups
            ?.get("name")
            ?.let { ScrapedArtist(name = deshout(it.value), role = "DJ") }

    private companion object {
        /** Squarespace puts U+202F before the meridiem, which `\s` does not match. */
        val DATE_LINE = Regex("""(?<date>\p{L}+ \d{1,2}, \d{4}),\s*(?<clock>\d{1,2}:\d{2})[\s\u202F\u00A0]*(?<meridiem>[AP]M)?""", RegexOption.IGNORE_CASE)

        val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM d, uuuu", Locale.ENGLISH)

        val LIVE = Regex("""\bLIVE\b""", RegexOption.IGNORE_CASE)

        val DJ = Regex("""\bDJ\b""", RegexOption.IGNORE_CASE)

        val RESIDENCY = Regex("""^(?<name>[^()]+?)\s*\([^)]+\)\s+.*\bDJ\s+RESIDENCY\b""", RegexOption.IGNORE_CASE)

        const val HOUSE = "8MM"

        /** `8MM Live:` or `8MM + Shameless Limitless pres:` in front of the acts. */
        val HOUSE_PREFIX = Regex("""^8MM\b[^:]*:\s*""", RegexOption.IGNORE_CASE)

        val HOUSE_SUFFIX = Regex("""\s+LIVE\s+in\s+8MM\s*$""", RegexOption.IGNORE_CASE)
    }
}
