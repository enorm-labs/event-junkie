package de.norm.events.scraper.mikropol

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ISO_DATE_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledTime
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripRelocationPrefix
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for Mikropol Berlin event detail pages (`/event/<date-slug>/`).
 *
 * One `.single-event` block: an `h1.entry-title`, an optional `h2.support` line, an inline
 * `.event-details` box (`DD.MM.YYYY` date, `Beginn` / `Einlass` times and an `Abendkasse` door price), a `.ticket-links`
 * Eventim button, an `a.event-image` poster, an `.eventnotes` description, and a `.promoter`
 * credit ("Trinity Music presents:") above the title. A sold-out / cancelled show carries a
 * `.canceledsoldout` badge (`Ausverkauft` / `Abgesagt`); a relocated show opens its title with
 * a "verlegt in den … –" note. No JSON-LD.
 *
 * The `Abendkasse` slot is empty on most pages, which stores no price. A free night prints
 * `<b>Abendkasse:</b> 0,00 €` there, and its notes say "Eintritt: Frei".
 *
 * Source for what the overview lacks — description, image, ticket URL, door price — and carries the shared
 * fields (date, times, status) too, so a successful fetch is a complete event; the overview
 * fills gaps (or stands in entirely when the fetch fails) via
 * [MikropolWebsiteImporter.fillGapsFromOverview].
 *
 * @see MikropolOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MikropolWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://mikropol-berlin.de/event/2026-07-14-house-of-protection/">Example detail page</a>
 */
class MikropolDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()

        val rawTitle = content.textAt("h1.entry-title")
        if (rawTitle == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }
        val title = cleanEventTitle(stripRelocationPrefix(rawTitle))

        val slug = extractEventSlug(sourceUrl, "/event/")
        val support = content.textAt("h2.support")
        val details = content.textAt("div.event-details").orEmpty()
        val statusBadge = content.textAt("div.canceledsoldout").orEmpty()
        val description = content.textAt("div.eventnotes")
        val boxOffice = parsePriceValue(BOX_OFFICE_PRICE.find(details)?.value)

        val eventType = inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            subtitle = support,
            description = description,
            eventType = eventType,
            // Prefer the slug's ISO date prefix, then the German `.eventdates` rendering.
            eventDate =
                parseIsoDate(slug.take(ISO_DATE_LENGTH))
                    ?: parseGermanDate(content.textAt("span.eventdates"))
                    ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledTime(details, "Einlass")),
            startTime = parseTime(labelledTime(details, "Beginn")),
            imageUrl =
                content.hrefAt("a.event-image")
                    ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.startsWith("http") },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MIKROPOL.sourceIdPrefix}$slug",
            ticketUrl = content.hrefAt("div.ticket-links a.ticket"),
            priceBoxOffice = boxOffice,
            free = detectFree(priceBoxOffice = boxOffice) || description?.let { FREE_ENTRY.containsMatchIn(it) } == true,
            // Sold-out and cancelled render in the `.canceledsoldout` badge; a relocation lives in the title.
            soldOut = statusBadge.contains(SOLD_OUT_TEXT, ignoreCase = true),
            status = parseEventStatus("$statusBadge $rawTitle"),
            statusNote = rawTitle,
            artists = buildArtistsForEventType(title, support, eventType),
            promoters = parsePromoters(content)
        )
    }

    /**
     * The promoter credit above the title — `<div class="promoter">Trinity Music presents:</div>` —
     * with its `presents:` / `präsentiert:` frame stripped (#1532). One name per credit; the venue
     * writes one promoter per show.
     */
    private fun parsePromoters(content: Element): List<String> =
        listOfNotNull(
            content
                .textAt("div.single-event div.promoter")
                ?.replace(PRESENTS_SUFFIX, "")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
        )

    private companion object {
        /** The Events-Manager sold-out badge text (`Ausverkauft`). */
        private const val SOLD_OUT_TEXT = "ausverkauft"

        /** The door price after its label; the value must follow directly, so an empty slot reads nothing. */
        private val BOX_OFFICE_PRICE = Regex("""(?<=Abendkasse:)[\s\u00a0]*\d+(?:[.,]\d{1,2})?[\s\u00a0]*€""", RegexOption.IGNORE_CASE)

        /** "Eintritt: Frei" in the notes; the shared free phrases have no colon. */
        private val FREE_ENTRY = Regex("""\beintritt\s*:?\s*frei\b""", RegexOption.IGNORE_CASE)

        /** The billing frame the venue appends to a promoter's name. */
        private val PRESENTS_SUFFIX = Regex("""\s*(?:presents|pr(?:ä|ae)sentiert)\s*:?\s*$""", RegexOption.IGNORE_CASE)
    }
}
