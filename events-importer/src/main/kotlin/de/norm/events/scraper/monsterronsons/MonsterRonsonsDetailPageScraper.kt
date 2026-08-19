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
 * The detail page repeats the card's title, date and time and adds the three things the listing
 * withholds: the night's prose, its door price, and — for the rare ticketed night — a ticket link.
 * It is therefore modelled as an *enrichment* of the overview event rather than a second source of
 * truth: [scrape] returns only those extra fields, and the importer merges them onto the card.
 *
 * Returning a partial model instead of a [de.norm.events.scraper.ScrapedEvent] is deliberate. The
 * page's own date strip carries the same year-less `6 Aug` as the card, so re-parsing it here would
 * duplicate the weekday inference for no gain, and a detail page that fails to load must never be
 * able to overwrite a date the card already resolved correctly.
 *
 * Three source quirks govern the parsing:
 *
 *  1. **Price lives in prose, and often as a time-banded tariff.** There is no price field. A quiet
 *     night states one amount (`€5`); a busy one prices by arrival time instead — free before 19:00,
 *     `€5` until 20:00, `€10` overnight, `€5` again at 03:00, free from 04:00. A single amount
 *     becomes the box-office price; several are kept verbatim in `priceNote`, because picking one of
 *     them would assert a door price the venue never charges for most of the night. The venue writes
 *     both `€5` and `5€`, sometimes in one list, so both spellings are matched — the shared
 *     [de.norm.events.scraper.parsePriceValue] only covers the latter.
 *  2. **Paragraphs must be read individually.** Webflow emits one `<p>` per line with no whitespace
 *     between them, so reading the body's text as a whole yields `… - FREE19:00 - 20:00 - €5`: the
 *     description becomes unreadable and a following start time fuses onto the amount before it.
 *  3. **The ticket button is always in the markup.** Webflow renders it on every night and hides it
 *     with `w-condition-invisible` when the CMS field is empty, so it is read through
 *     [hasVisibleWebflowFlag] rather than by presence.
 *
 * @see MonsterRonsonsOverviewPageScraper for the listing that supplies title, date, time and poster.
 */
class MonsterRonsonsDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the enrichable fields from a night's detail page.
     *
     * @param url the URL the document was fetched from, used to resolve a relative ticket link.
     * @return the extra fields, or null when the page carries no rich-text body at all — a shell
     *   page that would contribute nothing to merge.
     */
    fun scrape(
        document: Document,
        url: String
    ): MonsterRonsonsNightDetail? {
        // Paragraph by paragraph, not the body's own text(): Webflow emits each line as its own <p>,
        // and reading the container whole runs them together ("… - FREE19:00 - 20:00 - €5"), which
        // both mangles the description and lets a following time be read as part of a price.
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
            // One amount is the night's door price. Several means a time-banded tariff
            // (free before 19:00, €5, €10, €5, free again) — no single field can state that, so the
            // bands are kept verbatim as a note instead of one of them posing as *the* price.
            priceBoxOffice = prices.singleOrNull(),
            priceNote = paragraphs.filter { parsePricesIn(it).isNotEmpty() }.joinToString("; ").takeIf { prices.size > 1 },
            ticketUrl = parseTicketUrl(document, url)
        )
    }

    /**
     * Reads every amount stated in one paragraph.
     *
     * The venue writes both orders — `€5` and `5€` — sometimes in the same list, so both are
     * matched. Each pattern refuses an amount that runs straight into a digit or a colon, which is
     * what a following clock time looks like once Webflow's markup is flattened (`€5` + `20:00`).
     */
    private fun parsePricesIn(text: String): List<BigDecimal> =
        (EURO_LEADING_PRICE_PATTERN.findAll(text) + EURO_TRAILING_PRICE_PATTERN.findAll(text))
            .mapNotNull { match -> runCatching { BigDecimal(match.groupValues[1].replace(",", ".")) }.getOrNull() }
            .distinct()
            .toList()

    /** Reads the ticket link, but only when Webflow has not hidden the button as an empty CMS field. */
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
        /** One paragraph per line of the night's rich-text body: host blurb, running times, price bands. */
        private const val PARAGRAPH_SELECTOR = ".w-richtext p"

        /** Webflow's ticket button, rendered on every night and hidden when the CMS field is empty. */
        private const val TICKET_BUTTON_SELECTOR = "a.btn-parent.detail-pg"

        /** Label text the ticket button carries. */
        private const val TICKET_BUTTON_LABEL = "Tickets"

        /**
         * Matches a euro-sign-first amount as the venue usually writes it: `€5`, `€ 7,50`.
         * The lookahead rejects `€520:00`, which is `€5` with the next band's start time run into it.
         */
        private val EURO_LEADING_PRICE_PATTERN = Regex("""€[\s ]*(\d+(?:[.,]\d{1,2})?)(?![\d.,:])""")

        /** Matches the amount-first spelling the same venue also uses: `5€`, `7,50 €`. */
        private val EURO_TRAILING_PRICE_PATTERN = Regex("""(?<![\d.,:])(\d+(?:[.,]\d{1,2})?)[\s ]*€""")
    }
}

/**
 * The fields a Monster Ronson's night page adds to the card that announced it.
 *
 * Deliberately not a `ScrapedEvent`: the page supplies no date of its own worth trusting (see
 * [MonsterRonsonsDetailPageScraper]), so it describes an enrichment rather than an event — the same
 * modelling choice as `BarJederVernunftShow` and `HavannaWeeklyNight` (ADR-007).
 */
data class MonsterRonsonsNightDetail(
    /** The night's prose: who hosts it, when it runs, what it costs. */
    val description: String,
    /** Door price, set only when the night states exactly one amount. */
    val priceBoxOffice: BigDecimal? = null,
    /** The tariff lines verbatim, when the night prices by arrival time instead of naming one price. */
    val priceNote: String? = null,
    /** External ticket link, on the rare night that sells tickets in advance. */
    val ticketUrl: String? = null
) {
    /**
     * Applies this page's fields to the [event] the listing card produced.
     *
     * Only fills what the card could not carry — the card stays authoritative for title, date, time,
     * poster and hosts, all of which it states directly.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent =
        event.copy(
            description = event.description ?: description,
            priceBoxOffice = event.priceBoxOffice ?: priceBoxOffice,
            priceNote = event.priceNote ?: priceNote,
            ticketUrl = event.ticketUrl ?: ticketUrl
        )
}
