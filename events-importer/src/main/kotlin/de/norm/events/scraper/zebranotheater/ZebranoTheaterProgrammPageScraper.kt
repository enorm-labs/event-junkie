package de.norm.events.scraper.zebranotheater

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.classifyByGenreKeyword
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for the Zebrano-Theater `programm.html`, a hand-written HTML 4 page with one
 * `h2` (`Oktober 2026`) and one table per month. A row is weekday, `02.10.`, `19:30 Uhr`, the
 * billing, the price (`15*` and `20 €`, the starred one reduced), the house's category (`Rubrik`) and
 * two ticket icons. The year comes from the heading text; the heading `id` carries typos (`102027`).
 *
 * The billing links `programm/<yyyyMMdd>.shtml` and adds a subtitle and guest lines below it. A
 * row announced early prints its billing unlinked and `(Infos folgen)`. Some rows leave a `td` or
 * `tr` unclosed, which Jsoup's parser closes. The stage plays one show a night, so the date is the
 * identity.
 */
class ZebranoTheaterProgrammPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val months = document.select("h2").mapNotNull { heading -> parseMonth(heading.text())?.let { heading to it } }
        logger.info { "Found ${months.size} Zebrano-Theater month table(s) on $baseUrl" }
        val events =
            months.flatMap { (heading, month) ->
                val table = heading.nextElementSibling()?.takeIf { it.tagName() == "table" }
                if (table == null) {
                    logger.warn { "Zebrano-Theater month '${heading.text()}' has no programme table, skipping" }
                    emptyList()
                } else {
                    table.select("tr:has(> td)").mapSkippingFailures(logger, "Zebrano-Theater row") { row -> parseRow(row, month, baseUrl) }
                }
            }
        return withUniqueIds(events)
    }

    private fun parseRow(
        row: Element,
        month: LocalDate,
        baseUrl: String
    ): ScrapedEvent? {
        val cells = row.select("> td")
        if (cells.size < MIN_CELLS) return null
        val eventDate = parseDate(cells[DATE_CELL].text(), month) ?: return null
        val billing = cells[BILLING_CELL]
        val link = billing.selectFirst("a[href*=programm/]")
        val lines = billingLines(billing).filterNot { it == INFO_TO_FOLLOW }
        val title = (link?.text().blankToNull() ?: lines.firstOrNull())?.let(::normalizeTitle) ?: return null
        val subtitleLines = lines.drop(1)
        val subtitle = subtitleLines.joinToString("\n").blankToNull()
        val category = cells[CATEGORY_CELL].text().blankToNull()
        val mappedType = mapEventType(category, CATEGORY_TYPES) ?: category?.let(::classifyByGenreKeyword)
        val eventType = mappedType ?: EventType.SHOW.name
        val (boxOffice, priceNote) = parsePrice(cells[PRICE_CELL].text())
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            typeIsFallback = mappedType == null,
            eventDate = eventDate,
            startTime = parseTime(cells[TIME_CELL].text().removeSuffix(TIME_SUFFIX)),
            sourceUrl = link?.absUrl("href")?.blankToNull() ?: baseUrl,
            sourceId = "${EventSource.ZEBRANO_THEATER.sourceIdPrefix}${eventDate.format(DateTimeFormatter.BASIC_ISO_DATE)}",
            ticketUrl = row.selectFirst("a[href*=eventim]")?.absUrl("href")?.blankToNull(),
            priceBoxOffice = boxOffice,
            priceNote = priceNote,
            status = parseEventStatus(row.text()),
            soldOut = SOLD_OUT.containsMatchIn(row.text()),
            artists = ZebranoTheaterBilling.artists(title, subtitleLines, isConcert = eventType == EventType.CONCERT.name)
        )
    }

    /**
     * The billing cell as lines: a `br` ends a line, and so does the bold `span` that holds the
     * linked title, which some rows follow with text and no `br`. Comments hold old billings.
     */
    private fun billingLines(cell: Element): List<String> {
        val lines = mutableListOf(StringBuilder())
        cell.childNodes().forEach { node ->
            when {
                node is TextNode -> lines.last().append(node.text())

                node is Element && node.tagName() == "br" -> lines.add(StringBuilder())

                node is Element && node.selectFirst(
                    "a"
                ) != null && node.tagName() == "span" -> lines.last().append(node.text()).also { lines.add(StringBuilder()) }

                node is Element -> lines.last().append(node.text())
            }
        }
        return lines.mapNotNull { it.toString().replace(WHITESPACE, " ").blankToNull() }
    }

    /** `Johannes Kirchberg : Im Leuchtturm` loses the space before the colon. */
    private fun normalizeTitle(title: String): String = title.replace(WHITESPACE, " ").replace(SPACED_COLON, ": ").trim()

    /** `02.10.` in the month of its table; a January row under a December heading is the next year. */
    private fun parseDate(
        text: String,
        month: LocalDate
    ): LocalDate? {
        val match = DAY_MONTH.find(text) ?: return null
        val day = match.groupValues[1].toInt()
        val rowMonth = match.groupValues[2].toInt()
        val year = if (rowMonth < month.monthValue) month.year + 1 else month.year
        return runCatching { LocalDate.of(year, rowMonth, day) }.getOrNull()
    }

    /** A `15*` beside `20 €`: the starred figure is the reduced price, so the other is the box-office one. */
    private fun parsePrice(text: String): Pair<BigDecimal?, String?> {
        val amounts = AMOUNT.findAll(text).map { it.groupValues[1] to (it.groupValues[2] == "*") }.toList()
        val full = amounts.filterNot { it.second }.map { BigDecimal(it.first.replace(',', '.')) }
        val reduced = amounts.filter { it.second }.map { it.first }
        val note =
            when {
                amounts.isEmpty() -> text.blankToNull()
                reduced.isNotEmpty() -> "ermäßigt ${reduced.joinToString(" / ")} €"
                else -> null
            }
        return full.maxOrNull() to note
    }

    /** `Oktober 2026` as the first day of that month. */
    private fun parseMonth(text: String): LocalDate? {
        val parts = text.trim().split(WHITESPACE)
        if (parts.size != 2) return null
        val month = GERMAN_MONTHS.indexOf(parts[0].lowercase()).takeIf { it >= 0 } ?: return null
        val year = parts[1].toIntOrNull()?.takeIf { it in YEARS } ?: return null
        return LocalDate.of(year, Month.of(month + 1), 1)
    }

    /** Two shows on one date would share an id, so both then take their start time as well. */
    private fun withUniqueIds(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val shared =
            events
                .groupingBy { it.sourceId }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        return events.map { event ->
            if (event.sourceId in shared) {
                event.copy(sourceId = "${event.sourceId}-${event.startTime?.format(HOUR_MINUTE) ?: event.title.hashCode()}")
            } else {
                event
            }
        }
    }

    private companion object {
        const val MIN_CELLS = 6
        const val DATE_CELL = 1
        const val TIME_CELL = 2
        const val BILLING_CELL = 3
        const val PRICE_CELL = 4
        const val CATEGORY_CELL = 5
        const val TIME_SUFFIX = "Uhr"
        const val INFO_TO_FOLLOW = "(Infos folgen)"

        val YEARS = 2000..2100
        val GERMAN_MONTHS = GERMAN_MONTH_NAMES.map { it.lowercase() }
        val WHITESPACE = Regex("""\s+""")
        val SPACED_COLON = Regex("""\s+:\s*""")
        val DAY_MONTH = Regex("""(\d{1,2})\.(\d{1,2})\.""")
        val AMOUNT = Regex("""(\d+(?:,\d{1,2})?)\s*(\*?)""")
        val SOLD_OUT = Regex("""ausverkauft""", RegexOption.IGNORE_CASE)
        val HOUR_MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** The house's `Rubrik` words the shared table does not know. */
        val CATEGORY_TYPES =
            mapOf(
                "musik" to EventType.CONCERT.name,
                "chanson" to EventType.CONCERT.name,
                "liederabend" to EventType.CONCERT.name,
                "lesebühne" to EventType.READING.name,
                "szenische lesung" to EventType.READING.name,
                "poesie" to EventType.READING.name,
                "theater" to EventType.SHOW.name,
                "mixed show" to EventType.SHOW.name
            )
    }
}
