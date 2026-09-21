package de.norm.events.scraper.morphine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * Pure HTML parser for Morphine Raum event detail pages (`/events/<slug>`).
 *
 * The event renders as a `section.content.overlay` above the full `/events` listing — the same
 * listing the overview carries, repeated as navigation. Every selector is scoped to that
 * overlay; the whole document would pull the other nights' titles and dates in.
 *
 * The hand-coded Kirby template emits typed `div.block` boxes, four with data. `.block.day` is
 * the header `"Friday, 07.08.26, door  20:00"` plus a nested `ul.lineup` whose first entry's
 * time is the start and whose every name is a performer. `.block.paypal` is an advance-ticket
 * form: its hidden `amount` input is the presale price, and it posts to PayPal rather than
 * linking, so the button is the only way to buy and there is no ticket URL. `.block.priceevent`
 * is free text the venue also uses for house rules, so [readPriceNote] requires a pricing signal
 * and [parseDoorPrice] stores a figure only for a single unambiguous amount — a sliding scale or
 * donation range, most nights, has no field. `.block.paragraph` keeps its `<br>` breaks, which
 * is how the instrument credits are written ("Jon Rose | violin & field recordings").
 *
 * **The lineup names carry more than the act**, and the shared splitters resolve them only as
 * far as a structural signal allows: a `/`-separated co-bill without spaces stays one name,
 * because [splitHeadlinerTitle][de.norm.events.scraper.splitHeadlinerTitle] requires the padding
 * that protects `AC/DC`, and a `– <project>` tail stays attached for want of anything separating
 * it from a hyphenated act name. Both need a curated vocabulary, not a Morphine-local rule; see #302.
 *
 * @see MORPHINE_LIMITATIONS for what the source does not publish.
 * @see MorphineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MorphineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="http://www.morphinerecords.com/events/sardy-fardy-live-recording">Example detail page</a>
 */
class MorphineDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event overlay or title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing overlay and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val overlay = document.selectFirst("section.content.overlay")
        if (overlay == null) {
            logger.warn { "Detail page has no event overlay, skipping" }
            return null
        }

        val rawTitle = overlay.textAt("div.title")
        if (rawTitle == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }
        val title = cleanEventTitle(rawTitle)

        // The day header's own text; the nested `ul.lineup` is read separately.
        val dayLine = overlay.selectFirst("div.block.day")?.ownText()
        val lineup = readLineup(overlay)
        val priceNote = readPriceNote(overlay.textAt("div.block.priceevent > p"))

        return ScrapedEvent(
            title = title,
            description = readDescription(overlay),
            eventType = inferConcertVenueType(title),
            eventDate = parseDayLineDate(dayLine) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseDayLineDoors(dayLine),
            // The first set's time is when the night starts; later entries are sets within it.
            startTime = parseTime(lineup.firstOrNull()?.startTime),
            imageUrl = overlay.imgSrcAt("div.block.image img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.MORPHINE.sourceIdPrefix}${extractEventSlug(sourceUrl)}",
            pricePresale = readPaypalPrice(overlay),
            priceBoxOffice = parseDoorPrice(priceNote),
            priceNote = priceNote,
            artists =
                readPerformers(overlay).ifEmpty {
                    lineup
                        .filter { it.name.isNotBlank() }
                        .flatMap { morphineSetLineActs(stripLiveRecordingSuffix(it.name), title) }
                        .flatMap { headlinersFromTitle(it) }
                        .distinctBy { it.name.lowercase() }
                }
        )
    }

    /**
     * The performers of an ensemble piece from the first `.block.paragraph` whose every line is a
     * `Name (instrument, …)` credit — at least [MIN_PERFORMER_CREDITS], so one parenthesised
     * remark in prose is never a lineup. Such a piece is billed under the work's name ("VINYL
     * REDUCTION" is a turntable-quartet composition, not an act, #1134), so when the block exists
     * its names are the lineup and the work stays the title. Empty otherwise, and the `ul.lineup`
     * names are the acts.
     */
    private fun readPerformers(overlay: Element): List<ScrapedArtist> =
        overlay
            .select("div.block.paragraph p")
            .map { paragraph -> paragraph.textLines().map { it.trim() }.filter { it.isNotBlank() } }
            .firstOrNull { lines -> lines.size >= MIN_PERFORMER_CREDITS && lines.all { PERFORMER_CREDIT.matches(it) } }
            .orEmpty()
            .mapNotNull {
                PERFORMER_CREDIT
                    .find(it)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            }.filterNot { isNonArtistName(it) }
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it, role = "HEADLINER") }

    /**
     * The `ul.lineup` set entries, each an `<li>` of two spans: start time and billed name. An
     * entry missing either span, or blank in both, is skipped. One with a time and no name stays:
     * the venue writes a bare `20:30` under `door 20:00` when the night is billed by its title
     * alone, and that time is the start (#1497).
     */
    private fun readLineup(overlay: Element): List<LineupEntry> =
        overlay.select("div.block.day ul.lineup li").mapNotNull { item ->
            val spans = item.select("span")
            if (spans.size < SPANS_PER_ENTRY) {
                null
            } else {
                LineupEntry(startTime = spans[TIME_SPAN].text().trim(), name = spans[NAME_SPAN].text().trim())
                    .takeIf { it.name.isNotBlank() || it.startTime.isNotBlank() }
            }
        }

    /**
     * The programme text from every `.block.paragraph` box, keeping the `<br>` breaks the
     * instrument credits are written with, boxes separated by a blank line.
     */
    private fun readDescription(overlay: Element): String? =
        overlay
            .select("div.block.paragraph")
            .joinToString(PARAGRAPH_SEPARATOR) { block -> block.select("p").joinToString("\n") { it.textLines().joinToString("\n") } }
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * The presale price from the PayPal form's hidden inputs, or `null` without a form. Guarded on
     * `currency_code` so a non-EUR form is never stored as euros — `EventEntity` has no per-event
     * currency (all scraped venues are in Berlin).
     */
    private fun readPaypalPrice(overlay: Element): BigDecimal? =
        overlay
            .selectFirst("div.block.paypal form")
            ?.takeIf { it.attrAt("input[name=currency_code]", "value").equals(EUR, ignoreCase = true) }
            ?.attrAt("input[name=amount]", "value")
            ?.replace(',', '.')
            ?.toBigDecimalOrNull()

    /** One `ul.lineup` `<li>`: the set's start time as written, and the billed name. */
    private data class LineupEntry(
        val startTime: String,
        val name: String
    )

    private companion object {
        /** `Sofia Borges (turntable, prepared vinyls)` — one performer's credit line, name then instruments. */
        private val PERFORMER_CREDIT = Regex("""^([^()|:]+?)\s*\(([^()]+)\)$""")

        /** An ensemble has at least this many credit lines; one line is a remark, not a lineup. */
        private const val MIN_PERFORMER_CREDITS = 2

        /** A lineup entry wraps exactly two spans: the start time, then the billed name. */
        private const val SPANS_PER_ENTRY = 2
        private const val TIME_SPAN = 0
        private const val NAME_SPAN = 1

        /** The blank line between two `.block.paragraph` boxes in the joined description. */
        private const val PARAGRAPH_SEPARATOR = "\n\n"

        /** The only currency the PayPal forms use; see [readPaypalPrice]. */
        private const val EUR = "EUR"
    }
}
