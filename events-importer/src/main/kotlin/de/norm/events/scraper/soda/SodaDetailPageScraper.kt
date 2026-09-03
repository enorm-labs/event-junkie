package de.norm.events.scraper.soda

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HH_MM_LENGTH
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseIsoTime
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal

/**
 * Pure HTML parser for Soda Club Berlin event detail pages (`/de/events/<slug>`).
 *
 * Each page carries a `<script type="application/ld+json">` schema.org `MusicEvent`
 * block — the most stable source on the page (ADR-007 §"Selector Strategy" priority 1) —
 * supplying the start date and time, the flyer, the canonical URL, the event status, and
 * the online ticket offer. The rendered markup adds what the JSON-LD omits or truncates:
 * the clean `h1` title (the JSON-LD `name` appends the date and venue), the untruncated
 * prose blurb (`p.event-details`), and the labelled info boxes.
 *
 * Three quirks drive the parser:
 *  - the `Einlass` info box is an **age limit** ("Ab 18"), not a doors time — the venue
 *    publishes no doors time at all, so [ScrapedEvent.doorsTime] is always null;
 *  - the `Eintritt` box is the admission price, which is a box-office price only when the
 *    page also shows the "Abendkasse verfügbar" badge (see [parsePrices]);
 *  - the JSON-LD `performer` is always the placeholder `"Unbekannt"` and the `organizer`
 *    is the venue itself, so neither is read — every night is typed
 *    [PARTY][EventType.PARTY] and carries no artists or promoters.
 *
 * @see SodaOverviewPageScraper for overview parsing (discovery, year-less date fallback).
 * @see SodaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.soda-berlin.de/de/events/famous-friday-31-07-2026">Example detail page</a>
 */
class SodaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page has no
     * event title (an unexpected structure).
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clause for the missing title is clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.body()
        val jsonLd = parseMusicEventNode(document)

        val title = content.textAt("h1.title") ?: jsonLd?.stringOrNull("name")?.let(::stripNameSuffix)
        if (title == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
            return null
        }

        val startDate = jsonLd?.stringOrNull("startDate")
        val offers =
            jsonLd
                ?.path("offers")
                ?.takeIf { it.isArray }
                ?.toList()
                .orEmpty()
        val (pricePresale, priceBoxOffice, entryPrice) = parsePrices(content, offers)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content) ?: jsonLd?.stringOrNull("description"),
            // Soda is a discotheque: every listing is a resident club night, never a billed act.
            eventType = EventType.PARTY.name,
            eventDate = startDate?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            // The venue publishes no doors time — the "Einlass" box states an age limit.
            startTime = startDate?.let { parseIsoTime(it) } ?: parseTime(infoBoxValue(content, "Beginn")?.take(HH_MM_LENGTH)),
            imageUrl = jsonLd?.stringOrNull("image") ?: content.imgSrcAt("img.event-preview-image"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SODA.sourceIdPrefix}${sodaEventSlug(sourceUrl)}",
            ticketUrl = content.attrAt("a.ticket-btn", "href")?.let { resolveUrl(sourceUrl, it) },
            pricePresale = pricePresale,
            priceBoxOffice = priceBoxOffice,
            soldOut = isSoldOut(offers),
            // A €0 admission is the venue's free-entry marker; keep it explicit so it survives
            // even when the price itself is not stored as a box-office price.
            free = entryPrice?.signum() == 0,
            // The schema.org URL uses `EventScheduled` / `EventCancelled` / `EventPostponed`,
            // whose keywords parseEventStatus already recognizes.
            status = parseEventStatus(jsonLd?.stringOrNull("eventStatus").orEmpty())
        )
    }

    /**
     * Splits the page's pricing into (presale, box office, admission).
     *
     * The `Eintritt` info box states the **admission** price; the "Abendkasse verfügbar"
     * badge states whether it can be paid at the door. So the admission price becomes the
     * box-office price only when that badge is present — an open air that sells online
     * only (no badge) would otherwise be recorded as having a door price it does not
     * offer. The JSON-LD offer carries the online price, which includes the booking fee
     * and is therefore slightly above the stated admission (15,43 € vs. "15 €"). When the
     * venue offers neither a badge nor an online ticket, the admission price is the only
     * price it states, so it is recorded as the presale price rather than dropped.
     *
     * The raw admission price is returned alongside so the caller can read a €0 admission
     * as the free-entry marker it is, independently of which slot it landed in.
     */
    private fun parsePrices(
        content: Element,
        offers: List<JsonNode>
    ): Triple<BigDecimal?, BigDecimal?, BigDecimal?> {
        val entryPrice = parsePriceValue(infoBoxValue(content, "Eintritt"))
        val offerPrice = offers.firstNotNullOfOrNull { it.stringOrNull("price") }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        val boxOfficeAvailable =
            content.select(".rn-office-badge").any { it.text().contains("abendkasse", ignoreCase = true) }

        return Triple(
            offerPrice ?: entryPrice.takeIf { !boxOfficeAvailable },
            entryPrice.takeIf { boxOfficeAvailable },
            entryPrice
        )
    }

    /**
     * Whether every ticket the page offers is marked `schema.org/SoldOut`. An event with no
     * offers at all is never sold out — it simply sells no tickets online (the free resident
     * nights), so an empty list must not collapse to `all { … } == true`.
     */
    private fun isSoldOut(offers: List<JsonNode>): Boolean =
        offers.isNotEmpty() &&
            offers.all { it.stringOrNull("availability")?.contains("soldout", ignoreCase = true) == true }

    /**
     * Reads the value of the info box carrying [label] (e.g. `"Beginn"` → `"22:00 Uhr"`,
     * `"Eintritt"` → `"15 €"`), or `null` when the page shows no such box. Each box pairs a
     * `h4.title` value with a `p.description` label.
     */
    private fun infoBoxValue(
        content: Element,
        label: String
    ): String? =
        content
            .select(".service .content")
            .firstOrNull { it.textAt("p.description").equals(label, ignoreCase = true) }
            ?.textAt("h4.title")

    /**
     * Extracts the event blurb from `p.event-details`, keeping its `<br>`-delimited lines as
     * separate lines instead of the whitespace-flattened `.text()`. Returns `null` when the
     * page carries no blurb, letting the caller fall back to the (truncated) JSON-LD
     * description.
     */
    private fun parseDescription(content: Element): String? =
        content
            .textLinesAt("p.event-details")
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * Parses the page's JSON-LD blocks and returns the schema.org `MusicEvent` object node,
     * or `null` when there is none or none can be parsed. Soda wraps the block in an array,
     * so array elements are unwrapped before matching on the decoded `@type`.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed block must degrade to null, never abort the import
    private fun parseMusicEventNode(document: Document): JsonNode? =
        document
            .select("script[type=application/ld+json]")
            .map { it.data() }
            .firstNotNullOfOrNull { json ->
                try {
                    val root = jsonMapper.readTree(json)
                    (if (root.isArray) root.toList() else listOf(root))
                        .firstOrNull { it.stringOrNull("@type") == MUSIC_EVENT_TYPE }
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Soda JSON-LD block" }
                    null
                }
            }

    private companion object {
        /** The schema.org `@type` Soda uses for every event. */
        private const val MUSIC_EVENT_TYPE = "MusicEvent"
    }
}

/**
 * Strips the `" - <D. Month YYYY> - <venue>"` tail the JSON-LD `name` appends to the event
 * title ("Halloween in der Kulturbrauerei - Samstag - 31. Oktober 2026 - Soda Club Berlin"
 * → "Halloween in der Kulturbrauerei - Samstag"). Anchored on the German date, so a title
 * that legitimately contains " - " keeps it. Only used as the fallback when the page has no
 * `h1`; returns the input unchanged when the tail is absent or stripping would leave nothing.
 */
private fun stripNameSuffix(name: String): String {
    val stripped = name.replace(NAME_DATE_SUFFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/** The `" - 31. Oktober 2026 - Soda Club Berlin"` tail appended to the JSON-LD `name`. */
private val NAME_DATE_SUFFIX = Regex("""\s+-\s+\d{1,2}\.\s+\p{L}+\s+\d{4}\s+-\s+.*$""")
