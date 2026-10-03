package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * One page of the Wühlmäuse ticket shop: its products, and whether a full page says another may follow.
 */
data class WuehlmaeuseShopPage(
    val tickets: List<WuehlmaeuseTicket>,
    val full: Boolean
)

/** One ticket product: a price category for one performance. */
data class WuehlmaeuseTicket(
    val act: String,
    val programme: String?,
    val date: LocalDate,
    val start: LocalTime,
    val doors: LocalTime?,
    val price: BigDecimal?,
    val eventUrl: String,
    val ticketUrl: String,
    val imageUrl: String?,
    val cancelled: Boolean
)

/**
 * Pure JSON parser for the WooCommerce Store API of Die Wühlmäuse (`/wp-json/wc/store/v1/products`).
 *
 * The theatre sells each performance as one product per price category, so a show is several
 * products. A product's `name` carries everything, as lines split by `<br />`:
 * `Florian Schroeder – Reihe 1-10`, `Endlich glücklich`, `Berlin Die Wühlmäuse`,
 * `Sonntag, 03.10.2027 um 20:00 Uhr`, `Einlass ab: 19:00 Uhr`. The act line ends in the price
 * category, cut at the last dash. [toEvents] folds the categories of one performance into one event.
 *
 * A cancelled show says so in the act or the programme line (`KANN LEIDER NICHT STATTFINDEN`,
 * `abgesagt`); the marker leaves the title and sets the status.
 */
class WuehlmaeuseApiScraper {
    private val logger = KotlinLogging.logger {}

    private val mapper: JsonMapper = JsonMapper.builder().build()

    /** Parses one page; a body that does not parse is an empty last page. */
    fun scrapePage(json: String): WuehlmaeuseShopPage {
        val root =
            try {
                mapper.readTree(json)
            } catch (e: JacksonException) {
                logger.warn(e) { "Wühlmäuse shop page is not parseable JSON" }
                return WuehlmaeuseShopPage(emptyList(), full = false)
            }
        val products = root.takeIf { it.isArray }?.toList().orEmpty()
        return WuehlmaeuseShopPage(products.mapNotNull(::toTicket), full = products.size >= PER_PAGE)
    }

    /** One event per performance: the act, programme and time shared by its categories, the lowest price. */
    fun toEvents(tickets: List<WuehlmaeuseTicket>): List<ScrapedEvent> =
        tickets
            .groupBy { Triple(it.eventUrl, it.date, it.start) }
            .values
            .map { categories ->
                val cheapest = categories.minWithOrNull(compareBy(nullsLast()) { it.price }) ?: categories.first()
                val ticket = categories.first()
                val bill = listOfNotNull(ticket.act, ticket.programme).joinToString(" – ")
                ScrapedEvent(
                    title = ticket.act,
                    subtitle = ticket.programme,
                    eventType = EventType.COMEDY.name,
                    eventDate = ticket.date,
                    doorsTime = ticket.doors,
                    startTime = ticket.start,
                    imageUrl = categories.firstNotNullOfOrNull { it.imageUrl },
                    sourceUrl = ticket.eventUrl,
                    sourceId = sourceIdOf(ticket),
                    ticketUrl = cheapest.ticketUrl,
                    pricePresale = cheapest.price,
                    status = if (categories.any { it.cancelled }) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
                    // A performer's "<Name> – <Programme>" bill names the act; a series or a play does not.
                    artists = buildArtistsForEventType(bill, null, EventType.COMEDY.name)
                )
            }.sortedWith(compareBy({ it.eventDate }, { it.startTime }))

    /** The show's slug, date and clock: one show plays many nights, and one night can hold two shows. */
    private fun sourceIdOf(ticket: WuehlmaeuseTicket): String =
        "${EventSource.WUEHLMAEUSE.sourceIdPrefix}${ticket.eventUrl.trimEnd('/').substringAfterLast('/')}-${ticket.date}-${ticket.start.format(HHMM)}"

    @Suppress("ReturnCount") // Guard clauses for each required line are clearer than nesting
    private fun toTicket(product: JsonNode): WuehlmaeuseTicket? {
        val lines = product.stringOrNull("name")?.split(LINE_BREAK)?.map { decodeHtmlEntities(it) } ?: return null
        val performance = lines.firstNotNullOfOrNull { PERFORMANCE.find(it) }
        val permalink = product.stringOrNull("permalink")
        if (lines.size < MIN_LINES || performance == null || permalink == null) {
            logger.warn { "Wühlmäuse product '${lines.firstOrNull()}' has no performance line, skipping" }
            return null
        }
        val date = parseGermanDate(performance.groupValues[1]) ?: return null
        val start = parseTime(performance.groupValues[2]) ?: return null
        val actLine = lines[0].substringBeforeLast(" – ").trim()
        val programme = lines[1].takeIf { it.isNotBlank() }
        val cancelled = CANCELLED.containsMatchIn(actLine) || (programme != null && CANCELLED.containsMatchIn(programme))
        val prices = product.path("prices")
        return WuehlmaeuseTicket(
            act = actLine.replace(CANCELLED_TAIL, "").trim(),
            programme = programme?.replace(CANCELLED_TAIL, "")?.trim()?.takeIf { it.isNotEmpty() },
            date = date,
            start = start,
            doors = lines.firstNotNullOfOrNull { DOORS.find(it) }?.let { parseTime(it.groupValues[1]) },
            price =
                prices
                    .stringOrNull("price")
                    ?.toBigDecimalOrNull()
                    ?.movePointLeft(prices.path("currency_minor_unit").asInt(2))
                    ?.takeIf { it.signum() > 0 },
            eventUrl = permalink.substringBefore("/ticket/"),
            ticketUrl = permalink,
            imageUrl = product.path("images").firstOrNull()?.stringOrNull("src"),
            cancelled = cancelled
        )
    }

    private companion object {
        const val PER_PAGE = 100
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")
        const val MIN_LINES = 4
        val LINE_BREAK = Regex("""<br\s*/?>""")
        val PERFORMANCE = Regex("""(\d{2}\.\d{2}\.\d{4}) um (\d{1,2}:\d{2})""")
        val DOORS = Regex("""Einlass ab:?\s*(\d{1,2}:\d{2})""")
        val CANCELLED = Regex("""kann leider nicht stattfinden|abgesagt""", RegexOption.IGNORE_CASE)
        val CANCELLED_TAIL = Regex("""\s*[-–]\s*(?:ersatzlos\s+)?(?:kann leider nicht stattfinden|abgesagt)\b""", RegexOption.IGNORE_CASE)
    }
}
