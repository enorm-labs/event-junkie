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
 * Each page carries a `<script type="application/ld+json">` schema.org `MusicEvent` block —
 * the most stable source (ADR-007 §"Selector Strategy" priority 1) — with start date and time,
 * flyer, canonical URL, status and the online ticket offer. The markup adds what JSON-LD omits
 * or truncates: the clean `h1` title (the JSON-LD `name` appends date and venue), the full
 * blurb (`p.event-details`), and the labelled info boxes.
 *
 * Three quirks:
 * - the `Einlass` box is an **age limit** ("Ab 18"), not a doors time — the venue publishes
 * none, so [ScrapedEvent.doorsTime] is always null;
 * - the `Eintritt` box is the admission price, a box-office price only when the page also
 * shows the "Abendkasse verfügbar" badge; the JSON-LD offer is the shop's fee-inclusive figure
 * and never a price column (see [parsePrices]);
 * - the JSON-LD `performer` is always `"Unbekannt"` and the `organizer` the venue itself, so
 * neither is read — every night is [PARTY][EventType.PARTY] with no artists or promoters.
 *
 * @see SodaOverviewPageScraper for overview parsing (discovery, year-less date fallback).
 * @see SodaWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.soda-berlin.de/de/events/famous-friday-31-07-2026">Example detail page</a>
 */
class SodaDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper = JsonMapper.builder().build()

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
        val jsonLd = parseMusicEventNode(document)

        val title = content.textAt("h1.title") ?: jsonLd?.stringOrNull("name")?.let(::stripNameSuffix)
        if (title == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val startDate = jsonLd?.stringOrNull("startDate")
        val offers =
            jsonLd
                ?.path("offers")
                ?.takeIf { it.isArray }
                ?.toList()
                .orEmpty()
        val prices = parsePrices(content, offers)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content) ?: jsonLd?.stringOrNull("description"),
            // Soda is a discotheque: every listing is a resident club night, never a billed act.
            eventType = EventType.PARTY.name,
            eventDate = startDate?.let { parseIsoDate(it) } ?: UNRESOLVED_EVENT_DATE,
            // No doors time — the "Einlass" box states an age limit.
            startTime = startDate?.let { parseIsoTime(it) } ?: parseTime(infoBoxValue(content, "Beginn")?.take(HH_MM_LENGTH)),
            imageUrl = jsonLd?.stringOrNull("image") ?: content.imgSrcAt("img.event-preview-image"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SODA.sourceIdPrefix}${sodaEventSlug(sourceUrl)}",
            ticketUrl = content.attrAt("a.ticket-btn", "href")?.let { resolveUrl(sourceUrl, it) },
            pricePresale = prices.presale,
            priceBoxOffice = prices.boxOffice,
            priceNote = prices.note,
            soldOut = isSoldOut(offers),
            // A €0 admission is the free-entry marker; keep it explicit even when the price is not
            // stored as a box-office price.
            free = prices.admission?.signum() == 0,
            // The schema.org URL uses `EventScheduled` / `EventCancelled` / `EventPostponed`, whose
            // keywords parseEventStatus already recognizes.
            status = parseEventStatus(jsonLd?.stringOrNull("eventStatus").orEmpty())
        )
    }

    /**
     * Splits the page's pricing into presale, box office, a note, and the raw admission.
     *
     * The `Eintritt` box is the **admission** price, the only price the venue names, so it fills
     * both columns. The "Abendkasse verfügbar" badge says whether the door takes it: admission is
     * the box-office price only with that badge — an online-only open air would otherwise get a
     * door price it does not offer. It is the presale price when the page sells online, or when
     * it names no other price.
     *
     * The JSON-LD offer is the shop's price with booking fee, above the admission by a margin the
     * shop sets (15,43 € vs. "15 €", 27,17 € vs. "25 €"). Stored as presale it told a visitor that
     * buying ahead costs more than the door (#1583), so it stays out of the price columns and goes
     * in the note, where the detail page shows it under the two prices.
     *
     * The raw admission is returned too so the caller can read a €0 admission as the free-entry
     * marker, independently of which slot it landed in.
     */
    private fun parsePrices(
        content: Element,
        offers: List<JsonNode>
    ): Prices {
        val admission = parsePriceValue(infoBoxValue(content, "Eintritt"))
        val offerPrice = offers.firstNotNullOfOrNull { it.stringOrNull("price") }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        val boxOfficeAvailable =
            content.select(".rn-office-badge").any { it.text().contains("abendkasse", ignoreCase = true) }
        val soldOnline = offerPrice != null

        return Prices(
            // The shop's figure fills the presale slot only when the page names no admission at all.
            presale = admission?.takeIf { soldOnline || !boxOfficeAvailable } ?: offerPrice,
            boxOffice = admission.takeIf { boxOfficeAvailable },
            note = offerPrice?.takeIf { admission != null && it > admission }?.let { "online ${formatEuro(it)} inkl. Gebühren" },
            admission = admission
        )
    }

    /** The page's prices, split by where each belongs on the stored row. */
    private data class Prices(
        val presale: BigDecimal?,
        val boxOffice: BigDecimal?,
        val note: String?,
        val admission: BigDecimal?
    )

    /**
     * Whether every offered ticket is `schema.org/SoldOut`. An event with no offers is never sold
     * out — it sells nothing online (the free resident nights), so an empty list must not collapse
     * to `all { … } == true`.
     */
    private fun isSoldOut(offers: List<JsonNode>): Boolean =
        offers.isNotEmpty() &&
            offers.all { it.stringOrNull("availability")?.contains("soldout", ignoreCase = true) == true }

    /**
     * The value of the info box carrying [label] (`"Beginn"` → `"22:00 Uhr"`, `"Eintritt"` →
     * `"15 €"`), or `null`. Each box pairs a `h4.title` value with a `p.description` label.
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
     * The blurb from `p.event-details`, keeping its `<br>`-delimited lines instead of the
     * flattened `.text()`. `null` without a blurb, so the caller falls back to the truncated
     * JSON-LD description.
     */
    private fun parseDescription(content: Element): String? =
        content
            .textLinesAt("p.event-details")
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    /**
     * The schema.org `MusicEvent` object node from the page's JSON-LD, or `null`. Soda wraps the
     * block in an array, unwrapped before matching on the decoded `@type`.
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

        /** Renders `15.43` as the German `15,43 €` the venue's pages print. */
        private fun formatEuro(amount: BigDecimal): String = "${amount.setScale(2).toPlainString().replace('.', ',')} €"
    }
}

/**
 * Strips the `" - <D. Month YYYY> - <venue>"` tail the JSON-LD `name` appends ("Halloween in
 * der Kulturbrauerei - Samstag - 31. Oktober 2026 - Soda Club Berlin" → "Halloween in der
 * Kulturbrauerei - Samstag"). Anchored on the German date, so a title containing " - " keeps
 * it. Fallback when the page has no `h1`; unchanged when the tail is absent or stripping
 * would leave nothing.
 */
private fun stripNameSuffix(name: String): String {
    val stripped = name.replace(NAME_DATE_SUFFIX, "").trim()
    return stripped.ifBlank { name.trim() }
}

/** The `" - 31. Oktober 2026 - Soda Club Berlin"` tail appended to the JSON-LD `name`. */
private val NAME_DATE_SUFFIX = Regex("""\s+-\s+\d{1,2}\.\s+\p{L}+\s+\d{4}\s+-\s+.*$""")
