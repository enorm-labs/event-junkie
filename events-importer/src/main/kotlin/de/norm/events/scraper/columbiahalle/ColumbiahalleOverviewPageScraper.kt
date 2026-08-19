package de.norm.events.scraper.columbiahalle

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure HTML parser for Columbiahalle Berlin's `/veranstaltungen.html` programme.
 *
 * The Contao event list renders the whole upcoming programme as one flat, chronological stream of
 * two node kinds inside `.mod_eventlist`: a `.eventlist_monat` heading ("August 2026") followed by
 * the `.eventlist_event` cards belonging to that month. A card states only its weekday and day of
 * month, so the heading is what supplies the month and year — the stream is therefore walked in
 * document order with the current [YearMonth] carried along.
 *
 * Each card is self-contained: the act (`h2`), an optional tour/support line (`h3`), the booking
 * agency (`.veranstalter`), `Einlass`/`Beginn` times (`.zeit`), `VVK`/`AK` prices (`.preis`), a
 * ticket-shop link, a poster, an optional `.stoerer` status sticker, and the untruncated blurb in
 * the collapsed `.bandinfo` panel — so no detail page is fetched (the cards' "Kalender-Eintrag"
 * links serve an iCal file, not HTML).
 *
 * @see ColumbiahalleWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.columbiahalle.berlin/veranstaltungen.html">Columbiahalle programme</a>
 */
class ColumbiahalleOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve poster paths and to
     *   build the per-event anchor URL.
     * @return a list of [ScrapedEvent] instances extracted from the page, in listing order.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nodes = document.select(".mod_eventlist .eventlist_monat, .mod_eventlist .eventlist_event")
        logger.info { "Found ${nodes.count { it.hasClass(EVENT_CLASS) }} event card(s) on Columbiahalle overview" }

        var month: YearMonth? = null
        val events = mutableListOf<ScrapedEvent>()

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        for (node in nodes) {
            if (node.hasClass(MONTH_HEADING_CLASS)) {
                // A heading that doesn't parse voids the month rather than carrying the previous one
                // forward, which would silently file a whole month of events under the wrong date.
                month = parseGermanYearMonth(node.text())
                if (month == null) logger.warn { "Unparseable month heading '${node.text()}', skipping its events" }
            } else {
                try {
                    parseCard(node, month, baseUrl)?.let(events::add)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse event card, skipping" }
                }
            }
        }
        return events
    }

    /**
     * Parses a single `.eventlist_event` card into a [ScrapedEvent], or `null` when it lacks the
     * Contao event id, a title, or a resolvable date.
     */
    @Suppress("ReturnCount") // Guard clauses for the required id / title / date are clearer than nesting
    private fun parseCard(
        card: Element,
        month: YearMonth?,
        baseUrl: String
    ): ScrapedEvent? {
        val eventId = EVENT_ID_PATTERN.find(card.selectFirst("div.event_inhalt")?.id().orEmpty())?.groupValues?.get(1)
        if (eventId == null) {
            logger.warn { "Event card without a Contao event id, skipping" }
            return null
        }
        val title = card.textAt("h2")?.let(::cleanEventTitle) ?: return null

        val eventDate = month?.let { resolveDayOfMonth(card.textAt(".event_datum_tag"), it) }
        if (eventDate == null) {
            logger.warn { "No date resolved for '$title' (event $eventId), skipping" }
            return null
        }

        val subtitle = card.textAt("h3")
        val times = card.textAt(".zeit").orEmpty()
        val sticker = card.textAt(".stoerer").orEmpty()
        val eventType = inferConcertVenueType(title)
        val (presale, boxOffice, priceNote) = parsePrices(card)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = card.textAt(".bandinfo"),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = parseTime(labelledTime(times, DOORS_LABEL)),
            startTime = parseTime(labelledTime(times, START_LABEL)),
            imageUrl = parseImageUrl(card, baseUrl),
            // The venue publishes no per-event page; its own iCal export keys the event on the same
            // Contao id and points back at this listing anchor.
            sourceUrl = resolveUrl(baseUrl, "#$EVENT_ID_PREFIX$eventId"),
            sourceId = "${EventSource.COLUMBIAHALLE.sourceIdPrefix}$eventId",
            ticketUrl = card.hrefAt(".tickets a"),
            pricePresale = presale,
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            soldOut = sticker.contains(SOLD_OUT_TEXT, ignoreCase = true),
            status = parseEventStatus(sticker),
            artists = buildArtistsForEventType(title, subtitle, eventType),
            promoters = listOfNotNull(card.textAt(".veranstalter a"))
        )
    }

    /** Resolves the card's day-of-month text against the month heading, or `null` when it is not a valid day. */
    private fun resolveDayOfMonth(
        dayText: String?,
        month: YearMonth
    ) = dayText
        ?.trim()
        ?.toIntOrNull()
        ?.takeIf { it in 1..month.lengthOfMonth() }
        ?.let(month::atDay)

    /** Resolves the card's poster path (a site-relative `files/BilderCache/…`) against the listing URL. */
    private fun parseImageUrl(
        card: Element,
        baseUrl: String
    ): String? {
        val src =
            card
                .selectFirst(".event_image img")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { resolveUrl(baseUrl, src) }.getOrNull()
    }

    /**
     * Splits the `.preis` block into presale, box-office and a free-form note.
     *
     * The amounts live as `<br>`-separated `LABEL: <amount> €` lines in the block's first
     * paragraph — `AK` (Abendkasse) is the box-office price, `VVK` (Vorverkauf) the presale one.
     * The note captures what the two numbers cannot say: the venue's "zzgl. Gebühr" (plus booking
     * fee) footnote in `p.small`, and the `ab`-qualified tiered prices where the stated amount is
     * only the cheapest ticket — storing `ab 74,99 €` as a flat presale price would otherwise read
     * as the actual price. When either qualifier is present the whole block text is kept verbatim
     * as the note.
     */
    private fun parsePrices(card: Element): Triple<BigDecimal?, BigDecimal?, String?> {
        val block = card.selectFirst(".preis") ?: return Triple(null, null, null)
        val valueLines = block.select("p:not(.small)").flatMap { it.textLines() }
        val presale = valueLines.firstOrNull { VVK_LABEL_PATTERN.containsMatchIn(it) }
        val boxOffice = valueLines.firstOrNull { AK_LABEL_PATTERN.containsMatchIn(it) }

        val feeNote = block.textAt("p.small")
        val hasFromPrice = valueLines.any { FROM_PRICE_PATTERN.containsMatchIn(it) }
        val note = block.text().trim().takeIf { it.isNotBlank() && (feeNote != null || hasFromPrice) }

        return Triple(parsePriceValue(presale), parsePriceValue(boxOffice), note)
    }

    private companion object {
        /** Class marking a `.eventlist_monat` month heading in the event stream. */
        private const val MONTH_HEADING_CLASS = "eventlist_monat"

        /** Class marking a `.eventlist_event` card in the event stream. */
        private const val EVENT_CLASS = "eventlist_event"

        /** The `id` prefix Contao puts on each card's content div (`event_9743`). */
        private const val EVENT_ID_PREFIX = "event_"

        /** The Contao event id on `div.event_inhalt` — the event's only stable identity. */
        private val EVENT_ID_PATTERN = Regex("""^$EVENT_ID_PREFIX(\d+)$""")

        /** The German doors label in the `.zeit` column ("Einlass: 18:30 Uhr"). */
        private const val DOORS_LABEL = "Einlass"

        /** The German start label in the `.zeit` column ("Beginn: 20:00 Uhr"). */
        private const val START_LABEL = "Beginn"

        /** The `.stoerer` sticker text marking a sold-out show (rendered in either case). */
        private const val SOLD_OUT_TEXT = "ausverkauft"

        /** The presale (Vorverkauf) price label, word-anchored so it can't match inside another word. */
        private val VVK_LABEL_PATTERN = Regex("""\bVVK\b""", RegexOption.IGNORE_CASE)

        /** The box-office (Abendkasse) price label, word-anchored so `VVK` can't false-match on its letters. */
        private val AK_LABEL_PATTERN = Regex("""\bAK\b""", RegexOption.IGNORE_CASE)

        /**
         * A "from" qualifier on a tiered price — `VVK: ab 74,99 €`. `an` is matched too: it is the
         * venue's own typo for `ab` on one current event, and since this only decides whether the
         * raw text is kept as a note, a false match costs nothing but a slightly redundant note.
         */
        private val FROM_PRICE_PATTERN = Regex("""\b(ab|an)\b\s*\d""", RegexOption.IGNORE_CASE)
    }
}

/**
 * Matches the `HH:mm` time introduced by [label] in the flattened `.zeit` column
 * (`"Einlass: 18:30 Uhr Beginn: 20:00 Uhr"`), or `null` when the label is absent.
 */
private fun labelledTime(
    text: String,
    label: String
): String? = Regex("""$label\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

/**
 * Parses a German month heading ("August 2026", "März 2027") into a [YearMonth], or `null` when it
 * is not one — the only place the programme states a month and a year at all.
 */
internal fun parseGermanYearMonth(text: String): YearMonth? =
    try {
        YearMonth.parse(text.trim(), GERMAN_MONTH_HEADING_FORMATTER)
    } catch (_: DateTimeParseException) {
        null
    }

private val GERMAN_MONTH_HEADING_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMAN)
