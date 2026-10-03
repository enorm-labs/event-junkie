package de.norm.events.scraper.mehringhof

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.buildArtistsForEventType
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for one month of the Mehringhof-Theater programme, a table on an IONOS
 * MyWebsite page (`/programm/`, `/programm/november-2026/`).
 *
 * Each row is `01.10. 20 Uhr` | the act and programme, linked to the ticket shop | an empty cell
 * whose background colour is the availability: green plenty, yellow few, red sold out. The table
 * is hand-edited and carries no classes, so cells are read by position. The year is not printed,
 * but a shop link's slug ends in the full date (`…-am-03-10-2026`), and its product id is the
 * performance's identity. A `keine Vorstellung` row has no clock and is skipped; a show row
 * without a link takes the year of the page's other rows.
 *
 * A title reads `<Act> mit <Programme>`, `<Act>: <Programme>` or a series with guests (`FUN FACTS
 * mit …`); a performer's name before ` mit ` is billed.
 */
class MehringhofProgrammPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("tr").filter { row -> row.selectFirst("td")?.text()?.let { CLOCK.containsMatchIn(it) } == true }
        logger.info { "Found ${rows.size} Mehringhof performance(s) on $baseUrl" }
        // Every row of a page is one month; a row without a shop link takes its year from one with.
        val pageYear =
            document
                .select("a[href*=/produkte/]")
                .firstNotNullOfOrNull { PRODUCT.find(it.attr("href")) }
                ?.groupValues
                ?.get(YEAR)
                ?.toInt()

        @Suppress("TooGenericExceptionCaught") // Intentional: one malformed row must not abort the month
        return rows.mapNotNull { row ->
            try {
                parseRow(row, baseUrl, pageYear)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Mehringhof row, skipping" }
                null
            }
        }
    }

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun parseRow(
        row: Element,
        pageUrl: String,
        pageYear: Int?
    ): ScrapedEvent? {
        val cells = row.select("> td")
        if (cells.size < CELLS) return null
        val title =
            cells[1]
                .text()
                .replace(WHITESPACE, " ")
                .trim()
                .takeIf { it.isNotEmpty() } ?: return null
        val ticketUrl = cells[1].selectFirst("a[href*=/produkte/]")?.absUrl("href")
        val product = ticketUrl?.let { PRODUCT.find(it) }
        val dayMonth = DAY_MONTH.find(cells[0].text())
        val year = product?.groupValues?.get(YEAR)?.toInt() ?: pageYear
        if (dayMonth == null || year == null) return null
        val date = LocalDate.of(year, dayMonth.groupValues[2].toInt(), dayMonth.groupValues[1].toInt())
        val start =
            CLOCK
                .find(cells[0].text())
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?.let { LocalTime.of(it, 0) }
        return ScrapedEvent(
            title = title,
            eventType = EventType.COMEDY.name,
            eventDate = date,
            startTime = start,
            sourceUrl = ticketUrl ?: pageUrl,
            // The shop's product id where the row links one; a row without a link is keyed by its date and clock.
            sourceId = "${EventSource.MEHRINGHOF.sourceIdPrefix}${product?.groupValues?.get(1) ?: "$date-${start?.hour}"}",
            ticketUrl = ticketUrl,
            soldOut = SOLD_OUT_RED in cells[2].attr("style").replace(" ", ""),
            artists = buildArtistsForEventType(title.replaceFirst(" mit ", " – "), null, EventType.COMEDY.name)
        )
    }

    private companion object {
        const val CELLS = 3
        const val YEAR = 4
        const val SOLD_OUT_RED = "background-color:rgb(255,0,0)"
        val PRODUCT = Regex("""/produkte/(\d+)-[^?#]*-am-(\d{2})-(\d{2})-(\d{4})""")
        val DAY_MONTH = Regex("""(\d{1,2})\.(\d{1,2})\.""")
        val CLOCK = Regex("""(\d{1,2})\s*Uhr""")
    }
}
