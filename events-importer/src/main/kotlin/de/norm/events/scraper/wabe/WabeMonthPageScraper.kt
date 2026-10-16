package de.norm.events.scraper.wabe

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.resolveUrl
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth

/**
 * Parser for one WABE Jimdo month page (`/okt-2026/`). Every programme row is a four-column
 * `.j-hgrid`: dates and a grey category label, the poster, the billing, and the room with an
 * `Infos` button. A row may print several dates, one event each. The billing names the show on its
 * first line and the performers in capitals; a `Veranstaltung des …` line credits a guest house.
 * The year comes from the page URL, so a January night on the December page rolls over.
 */
class WabeMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val page = pageMonth(document, baseUrl) ?: error("No month and year in WABE page $baseUrl")
        val rows = document.select("#content_area .j-hgrid").filter { DATE.containsMatchIn(dateColumnText(it)) }
        logger.info { "Found ${rows.size} programme row(s) on WABE month page $baseUrl" }
        return rows
            .mapSkippingFailures(logger, "WABE programme row") { row -> parseRow(row, page, baseUrl) }
            .flatten()
            .distinctBy { it.sourceId }
    }

    private fun parseRow(
        row: Element,
        page: YearMonth,
        baseUrl: String
    ): List<ScrapedEvent>? {
        val columns = row.select("> .cc-m-hgrid-column")
        val dateText = dateColumnText(row)
        val billing =
            columns
                .drop(1)
                .firstOrNull { it.selectFirst("figure") == null && it.select(".j-text").isNotEmpty() }
                ?.let(::parseWabeBilling)
                ?: return null
        val category =
            DATE
                .findAll(dateText)
                .lastOrNull()
                ?.let { dateText.substring(it.range.last + 1) }
                ?.trim()
                ?.ifEmpty { null }
        val eventType = eventType(category, billing)
        val event = baseEvent(row, billing, category, eventType, baseUrl)
        return DATE
            .findAll(dateText)
            .mapNotNull { match -> dateOf(match, page)?.let { it to parseTime(match.groupValues[TIME].replace(".", ":")) } }
            .distinctBy { it.first }
            .map { (date, time) ->
                event.copy(
                    eventDate = date,
                    startTime = time,
                    sourceId = "${EventSource.WABE.sourceIdPrefix}$date-${SlugGenerator.slugify(billing.printedTitle)}"
                )
            }.toList()
    }

    private fun baseEvent(
        row: Element,
        billing: WabeBilling,
        category: String?,
        eventType: String,
        baseUrl: String
    ): ScrapedEvent {
        val ownPage = row.selectFirst("a.j-calltoaction-link[href^=/]")?.attr("href")?.let { resolveUrl(baseUrl, it) }
        val status = STATUS.find(row.text())?.let { parseEventStatus(it.value) }
        return ScrapedEvent(
            title = billing.title,
            subtitle = billing.subtitle,
            eventType = eventType,
            eventDate = LocalDate.MIN,
            imageUrl = posterUrl(row),
            sourceUrl = ownPage ?: baseUrl,
            sourceId = "",
            genre = category?.takeIf { eventType == EventType.CONCERT.name && it.lowercase() !in NON_GENRE_LABELS }?.replace(" | ", ", "),
            pricePresale = billing.presale,
            priceBoxOffice = billing.boxOffice,
            free = billing.free,
            soldOut = SOLD_OUT.containsMatchIn(row.text()),
            status = status ?: "SCHEDULED",
            artists = if (eventType in BILLED_TYPES) billing.artists else emptyList(),
            promoters = listOfNotNull(billing.presenter)
        )
    }

    /** The first column's text, one space between its lines, so the dates read as `Donnerstag 1 Oktober 20:00`. */
    private fun dateColumnText(row: Element): String =
        row
            .selectFirst("> .cc-m-hgrid-column")
            ?.let(::jimdoLines)
            ?.joinToString(" ")
            .orEmpty()

    /**
     * The date [match] names. The site sometimes prints the wrong month beside a weekday (`Freitag 2
     * November` on the October page), so a neighbouring month that fits the weekday wins when it is
     * the page's own month.
     */
    private fun dateOf(
        match: MatchResult,
        page: YearMonth
    ): LocalDate? {
        val month = Month.of(GERMAN_MONTH_NAMES.indexOfFirst { it.equals(match.groupValues[MONTH], ignoreCase = true) } + 1)
        val year =
            when {
                month.value < page.monthValue - HALF_YEAR -> page.year + 1
                month.value > page.monthValue + HALF_YEAR -> page.year - 1
                else -> page.year
            }
        val printed = runCatching { LocalDate.of(year, month, match.groupValues[DAY].toInt()) }.getOrNull() ?: return null
        val weekday = parseGermanWeekday(match.groupValues[WEEKDAY].replace("sonnabend", "samstag", ignoreCase = true))
        return dateCheckedAgainstWeekday(printed, weekday, match.value) { YearMonth.from(it) == page }
    }

    /** The category types the night; a row without one is a show when a theatre presents it. */
    private fun eventType(
        category: String?,
        billing: WabeBilling
    ): String {
        val mapped = mapEventType(category, TYPE_SYNONYMS) ?: category?.let(::typeByCategoryWord)
        val presentedTheatre = billing.presenter?.let { THEATRE_WORD.containsMatchIn(it) } == true
        return refineConcertVenueType(mapped ?: EventType.SHOW.name.takeIf { presentedTheatre }, billing.title)
    }

    /** A category the table does not spell out: a `Jazz | Pop`-style genre list is a concert, any theatre word a show. */
    private fun typeByCategoryWord(category: String): String? =
        when {
            THEATRE_WORD.containsMatchIn(category) -> EventType.SHOW.name
            category.contains("lesung", ignoreCase = true) -> EventType.READING.name
            else -> null
        }

    /** The widest `srcset` candidate of the row's poster; the `src` is the 225-pixel thumbnail. */
    private fun posterUrl(row: Element): String? {
        val img = row.selectFirst("figure img") ?: return null
        val widest =
            img
                .attr("srcset")
                .split(',')
                .map { it.trim().substringBefore(' ') }
                .lastOrNull { it.startsWith("http") }
        return widest ?: img.attr("src").takeIf { it.startsWith("http") }
    }

    private fun pageMonth(
        document: Document,
        baseUrl: String
    ): YearMonth? {
        val fromUrl =
            PAGE_SLUG
                .find(
                    baseUrl
                )?.let { YearMonth.of(it.groupValues[2].toInt(), parseGermanMonthAbbreviation(it.groupValues[1]) ?: return@let null) }
        return fromUrl ?: PAGE_HEADING.find(document.selectFirst("#content_area")?.text().orEmpty())?.let { heading ->
            val index = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(heading.groupValues[1], ignoreCase = true) }
            YearMonth.of(heading.groupValues[2].toInt(), index + 1)
        }
    }

    companion object {
        private const val WEEKDAY = 1
        private const val DAY = 2
        private const val MONTH = 3
        private const val TIME = 4
        private const val HALF_YEAR = 6

        private val MONTH_ALTERNATION = GERMAN_MONTH_NAMES.joinToString("|")

        private const val WEEKDAYS = "Montag|Dienstag|Mittwoch|Donnerstag|Freitag|Samstag|Sonnabend|Sonntag"

        /** One printed date: `Donnerstag 1 Oktober 20:00`, or `Sonnabend, 3. Oktober, 20:00 Uhr`; the time is optional. */
        private val DATE =
            Regex("""($WEEKDAYS),?\s+(\d{1,2})\.?\s+($MONTH_ALTERNATION)(?:,?\s+(\d{1,2}[:.]\d{2}))?""", RegexOption.IGNORE_CASE)

        private val PAGE_SLUG = Regex("""/([a-zä]{3})-(\d{4})/?$""", RegexOption.IGNORE_CASE)
        private val PAGE_HEADING = Regex("""\b($MONTH_ALTERNATION)\s+(\d{4})\b""", RegexOption.IGNORE_CASE)
        private val SOLD_OUT = Regex("""\bausverkauft\b""", RegexOption.IGNORE_CASE)
        private val STATUS = Regex("""\b(?:abgesagt|entfällt|fällt\s+aus|verschoben)\b""", RegexOption.IGNORE_CASE)
        private val THEATRE_WORD = Regex("""theater|schauspiel|monodrama""", RegexOption.IGNORE_CASE)

        /** Category labels that type the night but name no musical style. */
        private val NON_GENRE_LABELS = setOf("musik-show", "konzert")

        private val BILLED_TYPES = setOf(EventType.CONCERT.name, EventType.READING.name, EventType.COMEDY.name)

        private val TYPE_SYNONYMS =
            mapOf(
                "musik-show" to EventType.CONCERT.name,
                "jazz" to EventType.CONCERT.name,
                "pop" to EventType.CONCERT.name,
                "blues" to EventType.CONCERT.name,
                "jazz | pop" to EventType.CONCERT.name,
                "musikalische lesung" to EventType.READING.name,
                "impro-theater" to EventType.SHOW.name,
                "schauspiel" to EventType.SHOW.name
            )
    }
}
