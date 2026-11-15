package de.norm.events.scraper.humboldtforum

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SCRAPER_JSON
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure parser for `/wp-json/humboldt/v1/event/items`, which answers every current item in one `events` list.
 *
 * - **The venue's category types the item and sets the scope.** A tour, a workshop or another participation format drops the
 *   item whatever else it is filed under (EVENT_SCOPE.md §3.2), and so does a category that maps to no type: a publication, a
 *   shop, a symposium (§3.3). A digital, publication or permanent `type` is never an event.
 * - **An exhibition (`event-period`) is one run**, from the first date of its periods to the last. A run with no end is a
 *   permanent display, and is dropped.
 * - **Every other item gives one row per `acf.times` entry**: `start` is `yyyyMMdd HH:mm`, `end` only `HH:mm`. An end before
 *   the start crosses midnight only before 06:00; a later one is a typing slip and is dropped.
 * - The children's series `Bilderbuchkino` and `Geschichtentheater` are named only through `partof`, an id of another item.
 */
class HumboldtForumApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        json: String,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val items = SCRAPER_JSON.readTree(json).path("events").toList()
        val seriesTitles = items.associate { it.path("postId").asString("") to it.stringOrNull("title").orEmpty() }
        val events =
            items
                .mapSkippingFailures(logger, "Humboldt Forum item") { toEvents(it, seriesTitles) }
                .flatten()
                .distinctBy { it.sourceId }
        logger.info { "Found ${items.size} Humboldt Forum item(s), ${events.size} event(s) in scope from $sourceUrl" }
        return events
    }

    private fun toEvents(
        item: JsonNode,
        seriesTitles: Map<String, String>
    ): List<ScrapedEvent>? {
        val itemType = item.stringOrNull("type")
        val categories = item.path("genre_details").toList().mapNotNull { it.stringOrNull("text") }
        val type = typeOf(categories)
        if (itemType in NOT_EVENTS || type == null || categories.any { it in PARTICIPATION }) return null
        val base = baseEvent(item, type, seriesTitles) ?: return null
        val price =
            item
                .path("acf")
                .path("planning")
                .stringOrNull("price")
                ?.collapseWhitespace()
        val priced = base.withPrice(price)
        return if (itemType == EXHIBITION_RUN) {
            listOfNotNull(run(item, priced))
        } else {
            item
                .path("acf")
                .path("times")
                .toList()
                .mapNotNull { occurrence(it, priced, item.path("postId").asLong()) }
        }
    }

    private fun baseEvent(
        item: JsonNode,
        type: EventType,
        seriesTitles: Map<String, String>
    ): ScrapedEvent? {
        val title = item.stringOrNull("title")?.let(::plainText) ?: return null
        val excerpt = item.stringOrNull("excerpt")?.let(::plainText)
        val series = item.path("partof").toList().mapNotNull { seriesTitles[it.asString("")] }
        val place = item.path("place_details")
        return ScrapedEvent(
            title = title,
            description = excerpt,
            eventType = type.name,
            eventDate = LocalDate.MIN, // each row sets its own date
            room = place.stringOrNull("room") ?: place.stringOrNull("place")?.takeUnless { it == HOUSE_NAME },
            imageUrl = imageUrl(item.path("thumbnail")),
            sourceUrl = item.stringOrNull("permalink") ?: return null,
            sourceId = "${EventSource.HUMBOLDT_FORUM.sourceIdPrefix}${item.path("postId").asLong()}"
        ).forChildrenIf(childrensPhrase(title, excerpt, series))
    }

    private fun run(
        item: JsonNode,
        event: ScrapedEvent
    ): ScrapedEvent? {
        val dates =
            item
                .path("acf")
                .path("periods")
                .toList()
                .flatMap { it.path("dates").toList() }
                .filter { it.path("has_end").asBoolean(false) }
        val opening = dates.mapNotNull { periodDate(it.stringOrNull("start")) }.minOrNull() ?: return null
        val closing = dates.mapNotNull { periodDate(it.stringOrNull("end")) }.maxOrNull()?.takeIf { it >= opening } ?: return null
        return event.copy(
            eventDate = opening,
            endDate = closing.takeIf { it > opening },
            ticketUrl = item.path("acf").path("planning").stringOrNull("shop")
        )
    }

    private fun occurrence(
        time: JsonNode,
        event: ScrapedEvent,
        postId: Long
    ): ScrapedEvent? {
        val start = time.stringOrNull("start")?.let { LocalDateTime.parse(it, OCCURRENCE_START) } ?: return null
        val end = time.stringOrNull("end")?.let(LocalTime::parse)?.takeIf { it > start.toLocalTime() || it < NIGHT_ENDS }
        return event.copy(
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.let { if (it > start.toLocalTime()) start.toLocalDate() else start.toLocalDate().plusDays(1) },
            endTime = end,
            sourceId = "${EventSource.HUMBOLDT_FORUM.sourceIdPrefix}$postId-${start.format(OCCURRENCE_ID)}",
            ticketUrl = time.stringOrNull("shop"),
            status = if (time.stringOrNull("status") == CANCELED) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name
        )
    }

    private fun imageUrl(thumbnail: JsonNode): String? {
        val folder = thumbnail.stringOrNull("url") ?: return null
        val file = thumbnail.path("sizes").let { it.path("large").stringOrNull("file") ?: it.path("medium").stringOrNull("file") } ?: return null
        return resolveUrl(SITE, "$folder$file")
    }
}

/** `Kostenfrei` or another free-entry phrase is free; any other text gives its first amount and stays as the note when none parses. */
private fun ScrapedEvent.withPrice(price: String?): ScrapedEvent {
    val free = price != null && FREE_PRICE.containsMatchIn(price) && EXTRA_TICKET !in price
    val amount = parsePriceValue(price)
    return copy(
        free = free,
        pricePresale = amount.takeUnless { free },
        priceNote = price?.takeUnless { free || amount != null }
    )
}

/** The first type its categories name, in this order; null when none does. */
internal fun typeOf(categories: List<String>): EventType? = TYPE_ORDER.firstOrNull { (names, _) -> categories.any { it in names } }?.second

private fun childrensPhrase(
    title: String,
    excerpt: String?,
    series: List<String>
): String? =
    series.firstOrNull { it in CHILDRENS_SERIES }
        ?: CHILDRENS_TITLE.find(title)?.value
        ?: childrensShowPhrase(title, excerpt)

/** The API keeps the editors' no-break spaces, which [collapseWhitespace] does not count. */
private fun plainText(raw: String): String = decodeHtmlEntities(raw).replace('\u00A0', ' ').collapseWhitespace()

private fun periodDate(value: String?): LocalDate? = value?.takeIf { it.length >= DATE_DIGITS }?.let { LocalDate.parse(it.take(DATE_DIGITS), PERIOD_DATE) }

private const val SITE = "https://www.humboldtforum.org/"
private const val HOUSE_NAME = "Humboldt Forum"
private const val EXHIBITION_RUN = "event-period"
private const val CANCELED = "CANCELED"
private const val EXTRA_TICKET = "zzgl."
private const val DATE_DIGITS = 8

private val NOT_EVENTS = setOf("event-digital", "event-publication", "event-permanent")

/** EVENT_SCOPE.md §3.2: things a visitor takes part in. */
private val PARTICIPATION = setOf("Führung", "Workshop", "Drop-In", "Fortbildung", "Meetup")

private val TYPE_ORDER =
    listOf(
        setOf("Konzert") to EventType.CONCERT,
        setOf("DJ Set") to EventType.PARTY,
        setOf("Tanz", "Schauspiel", "Vorführung", "Performance") to EventType.SHOW,
        setOf("Film/Video") to EventType.SCREENING,
        setOf("Lesung", "Literatur", "Poetry", "Gespräch", "Diskurs", "Wissenschaft") to EventType.READING,
        setOf("Ausstellung") to EventType.EXHIBITION
    )

private val CHILDRENS_SERIES = setOf("Bilderbuchkino", "Geschichtentheater")
private val CHILDRENS_TITLE = Regex("""\bBilderbuch\w*""", RegexOption.IGNORE_CASE)

/** `free` only when the text opens with it; `kostenfrei zzgl. Ausstellungsticket` still needs a ticket. */
private val FREE_PRICE = Regex("""^(?:kostenfrei|freier\s+ein(?:t)?ritt|ein(?:t)?ritt\s+frei)\b""", RegexOption.IGNORE_CASE)

/** An end before the start crosses midnight only before this hour. */
private val NIGHT_ENDS: LocalTime = LocalTime.of(6, 0)

private val OCCURRENCE_START: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd HH:mm")
private val OCCURRENCE_ID: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm")
private val PERIOD_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
