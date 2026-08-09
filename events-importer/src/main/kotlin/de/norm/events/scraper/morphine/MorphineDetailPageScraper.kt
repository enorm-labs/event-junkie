package de.norm.events.scraper.morphine

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
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
 * A detail page renders the event as a `section.content.overlay` above the full `/events`
 * listing — the same listing the overview page carries, repeated as the page's navigation. Every
 * selector here is therefore scoped to that overlay; reading the document as a whole would pull
 * the other eleven nights' titles and dates into this event.
 *
 * Inside the overlay, the hand-coded Kirby template emits a `div.title` and a sequence of typed
 * `div.block` boxes, of which four carry data:
 * - **`.block.day`** — the one-line header `"Friday, 07.08.26, door  20:00"`, the source for the
 *   date and the door time, plus a nested `ul.lineup` of `<li>` set entries pairing a start time
 *   with the billed name. The first entry's time is the event's start time; every entry's name is
 *   read as a performer.
 * - **`.block.paypal`** — an advance-ticket PayPal form whose hidden `amount`/`currency_code`
 *   inputs give the presale price. It posts to PayPal rather than linking anywhere, so there is no
 *   ticket URL to store; the button is the only way to buy in advance.
 * - **`.block.priceevent`** — a free-text pricing line above the venue's address. It is read via
 *   [readPriceNote], which requires a pricing signal because the venue also uses this box for
 *   house rules, and via [parseDoorPrice], which only stores a box-office price when the line
 *   states a single unambiguous amount.
 * - **`.block.paragraph`** / **`.block.image`** — the programme text and the poster/press images.
 *   Paragraphs keep their `<br>` line breaks, because that is how the venue writes out the
 *   instrument credits ("Jon Rose | violin & field recordings"); the first image is stored.
 *
 * **What the source does not publish**, and which therefore stays empty rather than being
 * guessed: genre, a sold-out or cancelled marker (the venue removes a dropped night from the
 * listing instead of flagging it), a ticket URL, and a separate presale price for the nights
 * without a PayPal box. Pricing is a sliding scale or donation range on nearly every night, which
 * the data model has no field for — see [parseDoorPrice].
 *
 * **The lineup names carry more than the act**, and the shared splitters resolve them only as far
 * as a structural signal allows. A `/`-separated co-bill written without spaces stays one name
 * (`Kowa Axis/Aidan Baker/Tim Wyskida`), because
 * [splitHeadlinerTitle][de.norm.events.scraper.splitHeadlinerTitle] deliberately requires the
 * padding that protects `AC/DC`; and a `– <project>` or `– <member list>` tail stays attached,
 * because nothing separates it from a genuinely hyphenated act name. Both are tracked in issue #302 —
 * they need a curated vocabulary, not a Morphine-local rule.
 *
 * This class performs **no I/O** — it operates solely on a pre-fetched Jsoup [Document],
 * making it easy to test with a static fixture.
 *
 * @see MorphineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see MorphineWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="http://www.morphinerecords.com/events/sardy-fardy-live-recording">Example detail page</a>
 */
class MorphineDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event detail page into a [ScrapedEvent], or `null` when the page carries no event
     * overlay or no title (an unexpected structure).
     *
     * @param document the parsed Jsoup document of the detail page.
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive the
     *   [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing overlay and title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val overlay = document.selectFirst("section.content.overlay")
        if (overlay == null) {
            logger.warn { "Detail page at $sourceUrl has no event overlay, skipping" }
            return null
        }

        val rawTitle = overlay.textAt("div.title")
        if (rawTitle == null) {
            logger.warn { "Detail page at $sourceUrl has no event title, skipping" }
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
            artists = lineup.flatMap { headlinersFromTitle(stripLiveRecordingSuffix(it.name)) }.distinctBy { it.name.lowercase() }
        )
    }

    /**
     * Reads the `ul.lineup` set entries, each an `<li>` of two spans: the set's start time and the
     * billed name. An entry missing either span is skipped.
     */
    private fun readLineup(overlay: Element): List<LineupEntry> =
        overlay.select("div.block.day ul.lineup li").mapNotNull { item ->
            val spans = item.select("span")
            if (spans.size < SPANS_PER_ENTRY) {
                null
            } else {
                LineupEntry(startTime = spans[TIME_SPAN].text().trim(), name = spans[NAME_SPAN].text().trim())
                    .takeIf { it.name.isNotBlank() }
            }
        }

    /**
     * Joins the programme text from every `.block.paragraph` box, preserving the `<br>` line
     * breaks the venue writes its instrument credits with, and separating boxes by a blank line.
     */
    private fun readDescription(overlay: Element): String? =
        overlay
            .select("div.block.paragraph")
            .joinToString(PARAGRAPH_SEPARATOR) { block -> block.select("p").joinToString("\n") { it.textLines().joinToString("\n") } }
            .trim()
            .takeIf { it.isNotBlank() }

    /**
     * Reads the presale price from the advance-ticket PayPal form's hidden inputs, or `null` when
     * the night has no such form. Guarded on `currency_code` so a non-EUR form is never stored as
     * euros — `EventEntity` has no per-event currency (all scraped venues are in Berlin).
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
