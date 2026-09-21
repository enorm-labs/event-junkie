package de.norm.events.scraper.sisyphos

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Pure parser for the Sisyphos ticket shop's Shopify product feed
 * (`/collections/tickets/products.json`).
 *
 * The shop is the club's only website, and a ticketed night is a product of type `Ticket` whose
 * only date is in the title (`generationS 10. OKT 2026`). A product with no date in its title
 * or `body_html` — the Sauniphos sauna weekend names only weekdays — is skipped rather than
 * stored on a guessed day. A night whose every variant is unavailable is sold out, and the
 * lowest variant price is the online (presale) price.
 */
class SisyphosApiScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    /** Parses every dated ticket in the collection feed [json]; [baseUrl] resolves the product paths. */
    fun scrape(
        json: String,
        baseUrl: String
    ): List<ScrapedEvent> {
        val products = parseProducts(json) ?: return emptyList()
        logger.info { "Found ${products.size()} product(s) in Sisyphos ticket feed" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed products without aborting the import
        return products
            .filter { it.stringOrNull("product_type") == TICKET_TYPE }
            .mapNotNull { node ->
                try {
                    parseProduct(node, baseUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Sisyphos product, skipping" }
                    null
                }
            }
    }

    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to null, never abort the import
    private fun parseProducts(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Sisyphos ticket feed" }
                return null
            }
        return root.path("products").takeIf { it.isArray } ?: run {
            logger.warn { "Sisyphos ticket feed carries no products array" }
            null
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required handle, title and date are clearer than nesting
    private fun parseProduct(
        node: JsonNode,
        baseUrl: String
    ): ScrapedEvent? {
        val handle = node.stringOrNull("handle")
        if (handle == null) {
            logger.warn { "Sisyphos product has no handle, skipping" }
            return null
        }
        val rawTitle = node.stringOrNull("title")
        if (rawTitle == null) {
            logger.warn { "Sisyphos product '$handle' has no title, skipping" }
            return null
        }
        val description = htmlToText(node.stringOrNull("body_html"))
        val (title, eventDate) = splitTitleAndDate(rawTitle, description)
        if (eventDate == null) {
            logger.warn { "Sisyphos product '$handle' names no date, skipping" }
            return null
        }

        val variants = node.path("variants").filter { it.isObject }
        val productUrl = resolveUrl(baseUrl, "/products/$handle")
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            imageUrl =
                node
                    .path("images")
                    .firstOrNull()
                    ?.stringOrNull("src")
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = productUrl,
            sourceId = "${EventSource.SISYPHOS.sourceIdPrefix}$handle",
            ticketUrl = productUrl,
            pricePresale = variants.mapNotNull { it.stringOrNull("price")?.toBigDecimalOrNull() }.minOrNull(),
            soldOut = variants.isNotEmpty() && variants.none { it.path("available").asBoolean(false) }
        )
    }

    /**
     * The night's date out of [rawTitle] (`generationS 10. OKT 2026`), else out of the
     * [description], and the title with the date removed. The date is null when neither names one.
     */
    private fun splitTitleAndDate(
        rawTitle: String,
        description: String?
    ): Pair<String, LocalDate?> {
        val inTitle = DATE_PATTERN.find(rawTitle)?.let { it to parseDate(it) }
        if (inTitle?.second != null) {
            val title = rawTitle.removeRange(inTitle.first.range).trim(' ', '-', '–', '|', ',')
            return cleanEventTitle(title.ifBlank { rawTitle }) to inTitle.second
        }
        val inBody = description?.let { DATE_PATTERN.findAll(it).firstNotNullOfOrNull(::parseDate) }
        return cleanEventTitle(rawTitle) to inBody
    }

    /** Turns one [DATE_PATTERN] match into a date, accepting `10. OKT 2026`, `10. Oktober 2026` and `10.10.2026`. */
    private fun parseDate(match: MatchResult): LocalDate? {
        val (day, monthText, year) = match.destructured
        parseGermanDate("$day.$monthText.$year")?.let { return it }
        val month = parseGermanMonthAbbreviation(monthText) ?: parseGermanMonthAbbreviation(monthText.take(MONTH_ABBREVIATION_LENGTH))
        return month?.let {
            try {
                LocalDate.of(year.toInt(), it, day.toInt())
            } catch (_: DateTimeException) {
                null
            }
        }
    }

    /** Flattens the Shopify `body_html` blurb to trimmed plain text, keeping the shop's own line breaks. */
    private fun htmlToText(html: String?): String? =
        html
            ?.let { Jsoup.parseBodyFragment(it).wholeText() }
            ?.replace('\u00A0', ' ')
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.joinToString("\n")
            .blankToNull()

    private fun String.toBigDecimalOrNull(): BigDecimal? = runCatching { BigDecimal(this) }.getOrNull()

    private companion object {
        /** Shopify's `product_type` for a ticket; the same collection also carries merch bundles. */
        const val TICKET_TYPE = "Ticket"

        /** `10. OKT 2026`, `10. Oktober 2026` or `10.10.2026` — day, month, four-digit year. */
        val DATE_PATTERN = Regex("""\b(\d{1,2})\.\s*([A-Za-zÄÖÜäöü]{3,9}|\d{1,2})\.?\s*(\d{4})\b""")

        const val MONTH_ABBREVIATION_LENGTH = 3
    }
}
