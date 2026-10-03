package de.norm.events.scraper.scheinbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month

/**
 * Pure HTML parser for the Scheinbar Varieté programme (`/programm/`), a ProcessWire page that lists
 * about three months under `h2.month` headings (`Oktober 2026`). Each `li` is one evening: weekday,
 * day and clock, two text lines, the box-office price, an `ausverkauft` badge, and a reservation
 * link per date.
 *
 * The two lines take two shapes. The Open Stage nights read `Open Stage Varieté präsentiert von` /
 * `<host>`: the host is the one constant act of the night and is billed. A guest show reads
 * `<performers>` / `<programme>`, and the performers are split from the first line.
 */
class ScheinbarProgrammPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val headings = document.select("h2.month")
        logger.info { "Found ${headings.size} Scheinbar month(s) on $baseUrl" }
        return headings.flatMap { heading ->
            val month = parseMonth(heading.text())
            val list = heading.nextElementSibling()?.takeIf { it.hasClass("programm_uebersicht") }
            if (month == null || list == null) {
                logger.warn { "Scheinbar month heading '${heading.text()}' has no readable programme, skipping" }
                emptyList()
            } else {
                list.select("> li").mapNotNull { item -> parseItemSafely(item, month) }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: one malformed evening must not abort the page
    private fun parseItemSafely(
        item: Element,
        month: Pair<Month, Int>
    ): ScrapedEvent? =
        try {
            parseItem(item, month)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse Scheinbar evening, skipping" }
            null
        }

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun parseItem(
        item: Element,
        month: Pair<Month, Int>
    ): ScrapedEvent? {
        val first = item.textAt(".prog_desc .h3") ?: return null
        val second = item.textAt(".prog_desc .h2")
        val day = item.textAt(".date_time .date")?.toIntOrNull() ?: return null
        val eventDate = LocalDate.of(month.second, month.first, day)
        val sourceUrl = item.selectFirst("a[href^=/programm/]")?.absUrl("href") ?: return null
        val host = second?.takeIf { first.endsWith(PRESENTED_BY) }
        val priceText = item.selectFirst(".preis")?.ownText()?.trim()
        val prices = AMOUNT.findAll(priceText.orEmpty()).map { BigDecimal(it.value.replace(',', '.')) }.toList()
        return ScrapedEvent(
            title = if (host != null) "$first $host" else first,
            subtitle = second.takeIf { host == null },
            eventType = EventType.SHOW.name,
            eventDate = eventDate,
            startTime = parseTime(item.textAt(".date_time .time")),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.SCHEINBAR.sourceIdPrefix}$eventDate",
            ticketUrl = item.selectFirst("a.reservieren-link[href]")?.absUrl("href"),
            priceBoxOffice = prices.firstOrNull(),
            // `18,00 / 15,00 €` is the full and the reduced price.
            priceNote = priceText?.takeIf { prices.size > 1 },
            soldOut = item.selectFirst(".ausverkauft") != null,
            artists = if (host != null) listOf(ScrapedArtist(name = host)) else headlinersFromTitle(first)
        )
    }

    /** `Oktober 2026` as month and year. */
    private fun parseMonth(text: String): Pair<Month, Int>? {
        val parts = text.trim().split(' ')
        val month = GERMAN_MONTHS.indexOf(parts.first().lowercase()).takeIf { it >= 0 }?.let { Month.of(it + 1) }
        val year = parts.getOrNull(1)?.toIntOrNull()
        return if (parts.size == 2 && month != null && year != null) month to year else null
    }

    private companion object {
        const val PRESENTED_BY = "präsentiert von"
        val AMOUNT = Regex("""\d+,\d{2}""")
        val GERMAN_MONTHS =
            listOf("januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september", "oktober", "november", "dezember")
    }
}
