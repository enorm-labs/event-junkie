package de.norm.events.scraper.monsterronsons

import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.hasVisibleWebflowFlag
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.math.BigDecimal

/**
 * Pure HTML parser for a single Monster Ronson's night page (`/posts/<slug>`).
 *
 * The page repeats the card's title, date and time and adds what the listing withholds: the
 * night's prose, its door price, and for the rare ticketed night a ticket link. So it is an
 * *enrichment* of the overview event, not a second source of truth: [scrape] returns only those
 * extra fields and the importer merges them onto the card.
 *
 * A partial model instead of a [de.norm.events.scraper.ScrapedEvent] is deliberate: the page's
 * date strip carries the same year-less `6 Aug` as the card, so re-parsing it would duplicate
 * the weekday inference, and a detail page that fails to load must never overwrite a date the
 * card already resolved.
 *
 * 1. **Price lives in prose, often as a time-banded tariff.** No price field. A quiet night
 * states one amount (`€5`); a busy one prices by arrival — free before 19:00, `€5` until 20:00,
 * `€10` overnight, `€5` again at 03:00, free from 04:00. One amount is the box-office price;
 * several are kept verbatim in `priceNote`, since picking one would assert a door price the
 * venue never charges for most of the night. Both `€5` and `5€` occur, sometimes in one list,
 * so both are matched — the shared [de.norm.events.scraper.parsePriceValue] covers only the latter.
 * 2. **Paragraphs must be read individually.** Webflow emits one `<p>` per line with no
 * whitespace between, so the body's text as a whole reads `… - FREE19:00 - 20:00 - €5`.
 * 3. **The ticket button is always in the markup**, hidden with `w-condition-invisible` when
 * the CMS field is empty, so it is read through [hasVisibleWebflowFlag], not by presence.
 *
 * @see MonsterRonsonsOverviewPageScraper for the listing that supplies title, date, time and poster.
 */
class MonsterRonsonsDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the enrichable fields from a night's detail page.
     *
     * @param url the URL the document was fetched from, for resolving a relative ticket link.
     * @return the extra fields, or null when the page has no rich-text body — a shell page with
     * nothing to merge.
     */
    fun scrape(
        document: Document,
        url: String
    ): MonsterRonsonsNightDetail? {
        // Paragraph by paragraph, not the body's text(): Webflow emits each line as its own <p>, and
        // the container whole runs them together ("… - FREE19:00 - 20:00 - €5"), mangling the
        // description and letting a following time read as part of a price.
        val paragraphs =
            document
                .select(PARAGRAPH_SELECTOR)
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
        if (paragraphs.isEmpty()) {
            logger.debug { "No rich-text body on $url" }
            return null
        }

        val prices = paragraphs.flatMap(::parsePricesIn)
        return MonsterRonsonsNightDetail(
            description = paragraphs.joinToString("\n"),
            // One amount is the door price. Several is a time-banded tariff (free before 19:00, €5, €10,
            // €5, free again) — no field can state that, so the bands stay verbatim as a note rather than
            // one of them posing as *the* price.
            priceBoxOffice = prices.singleOrNull(),
            priceNote = paragraphs.filter { parsePricesIn(it).isNotEmpty() }.joinToString("; ").takeIf { prices.size > 1 },
            ticketUrl = parseTicketUrl(document, url)
        )
    }

    /**
     * Every amount in one paragraph. Both orders — `€5` and `5€` — sometimes in one list, so both
     * match. Each pattern refuses an amount running straight into a digit or colon, which is what
     * a following clock time looks like once the markup is flattened (`€5` + `20:00`).
     */
    private fun parsePricesIn(text: String): List<BigDecimal> =
        (EURO_LEADING_PRICE_PATTERN.findAll(text) + EURO_TRAILING_PRICE_PATTERN.findAll(text))
            .mapNotNull { match -> runCatching { BigDecimal(match.groupValues[1].replace(",", ".")) }.getOrNull() }
            .distinct()
            .toList()

    /** The ticket link, only when Webflow has not hidden the button as an empty CMS field. */
    @Suppress("ReturnCount") // Guard clauses for the hidden button and the placeholder href read clearer than nesting
    private fun parseTicketUrl(
        document: Document,
        url: String
    ): String? {
        if (!document.hasVisibleWebflowFlag(TICKET_BUTTON_SELECTOR, TICKET_BUTTON_LABEL)) return null
        val href = document.hrefAt(TICKET_BUTTON_SELECTOR)?.takeIf { it.isNotBlank() && it != "#" } ?: return null
        return resolveUrl(url, href)
    }

    companion object {
        /** One paragraph per line of the rich-text body: host blurb, running times, price bands. */
        private const val PARAGRAPH_SELECTOR = ".w-richtext p"

        /** Webflow's ticket button, rendered on every night and hidden when the CMS field is empty. */
        private const val TICKET_BUTTON_SELECTOR = "a.btn-parent.detail-pg"

        /** Label text the ticket button carries. */
        private const val TICKET_BUTTON_LABEL = "Tickets"

        /**
         * A euro-sign-first amount as the venue usually writes it: `€5`, `€ 7,50`. The lookahead
         * rejects `€520:00` — `€5` with the next band's start time run into it.
         */
        private val EURO_LEADING_PRICE_PATTERN = Regex("""€[\s ]*(\d+(?:[.,]\d{1,2})?)(?![\d.,:])""")

        /** The amount-first spelling the same venue also uses: `5€`, `7,50 €`. */
        private val EURO_TRAILING_PRICE_PATTERN = Regex("""(?<![\d.,:])(\d+(?:[.,]\d{1,2})?)[\s ]*€""")
    }
}

/**
 * The fields a night page adds to the card that announced it. Not a `ScrapedEvent`: the page
 * supplies no date worth trusting (see [MonsterRonsonsDetailPageScraper]), so it is an
 * enrichment, not an event — as `BarJederVernunftShow` and `HavannaWeeklyNight` (ADR-007).
 */
data class MonsterRonsonsNightDetail(
    /** The night's prose: who hosts it, when it runs, what it costs. */
    val description: String,
    /** Door price, set only when the night states exactly one amount. */
    val priceBoxOffice: BigDecimal? = null,
    /** The tariff lines verbatim, when the night prices by arrival time instead of one price. */
    val priceNote: String? = null,
    /** External ticket link, on the rare night that sells tickets in advance. */
    val ticketUrl: String? = null
) {
    /**
     * Applies this page's fields to the [event] the card produced. Only fills what the card could
     * not carry — the card stays authoritative for title, date, time, poster and hosts.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            description = event.description ?: description,
            priceBoxOffice = event.priceBoxOffice ?: priceBoxOffice,
            priceNote = event.priceNote ?: priceNote,
            ticketUrl = event.ticketUrl ?: ticketUrl
        )
}
