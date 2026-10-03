package de.norm.events.scraper.quatsch

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.stringOrNull
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** The calendar plugin's settings as the tickets page prints them: where to ask, the nonce, the city and every day with a show. */
data class QuatschCalendar(
    val ajaxUrl: String,
    val nonce: String,
    val city: String,
    val days: List<LocalDate>
)

/**
 * Pure parser for the Quatsch Comedy Club's Eventim calendar plugin, which answers one day per request.
 *
 * - An `auto` show comes from the club's Eventim box office. Its `event_id` keys it and names its shop page.
 * - A `manual` show is a guest production entered by hand, with an outside ticket link and no id, so its date and title key it.
 * - A house show names its host (`Moderiert von:`) and its comedians (`mit:`) in `event_subtitle_*`; a guest show puts prose there.
 * - Doors are an `Einlass:` line among `ad_text_*`, in German, English or Spanish; the other lines are the blurb.
 */
class QuatschCalendarScraper {
    private val logger = KotlinLogging.logger {}

    /** The settings in [document], or null when the page no longer carries them. */
    fun calendar(document: Document): QuatschCalendar? {
        val settings = document.select("script").firstNotNullOfOrNull { SETTINGS.find(it.data())?.groupValues?.get(1) }?.let(::readTree) ?: return null
        val city = document.selectFirst("#eventim_calendar_block[data-city]")?.attr("data-city").blankToNull()
        val ajaxUrl = settings.stringOrNull("ajax_url")
        val nonce = settings.stringOrNull("security")
        val days =
            settings
                .path("dates_in_order")
                .mapNotNull { day -> parseDate(day.asString("")) }
                .distinct()
                .sorted()
        return if (city == null || ajaxUrl == null || nonce == null) null else QuatschCalendar(ajaxUrl = ajaxUrl, nonce = nonce, city = city, days = days)
    }

    /** The shows in one day's answer. A day without one answers with an object, which yields none. */
    fun scrapeDay(json: String): List<ScrapedEvent> =
        readTree(json)
            ?.takeIf { it.isArray }
            ?.mapSkippingFailures(logger, "a Quatsch Comedy Club show") { show ->
                toScrapedEvent(show)
            }.orEmpty()

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun toScrapedEvent(show: JsonNode): ScrapedEvent? {
        val date = show.stringOrNull("real_date")?.let(::parseDate) ?: return null
        val rawTitle = show.text("event_title")
        if (rawTitle == null) {
            logger.warn { "Quatsch Comedy Club show on $date has no title, skipping" }
            return null
        }
        val title = cleanEventTitle(rawTitle.replace(HOUSE_PREFIX, ""))
        val subtitles = listOf("event_subtitle_1", "event_subtitle_2").flatMap { show.lines(it) }
        val adLines = (1..AD_TEXT_FIELDS).flatMap { show.lines("ad_text_$it") }
        val ticketUrl = ticketUrl(show)
        val key = show.stringOrNull("event_id")?.let { "eventim-$it" } ?: "$date-${SlugGenerator.slugify(title)}"

        return ScrapedEvent(
            title = title,
            description = (subtitles + adLines).filterNot(::isCreditOrDoors).joinToString("\n").blankToNull(),
            eventType = EventType.COMEDY.name,
            eventDate = date,
            doorsTime = adLines.firstNotNullOfOrNull(::doorsTime),
            startTime = startTime(show.path("event_time")),
            room = BAR_ROOM.takeIf { title.contains(it) },
            imageUrl = show.stringOrNull("event_image_url"),
            sourceUrl = ticketUrl ?: CALENDAR_URL,
            sourceId = "${EventSource.QUATSCH.sourceIdPrefix}$key",
            ticketUrl = ticketUrl,
            artists = artists(title, subtitles)
        )
    }

    /** The box office's shop page for an `auto` show, the hand-entered link for a guest show, without its tracking parameter. */
    private fun ticketUrl(show: JsonNode): String? =
        show.stringOrNull("event_id")?.let { "$SHOP_URL$it" }
            ?: show.stringOrNull("event_link")?.takeIf { it.startsWith("http") }?.replace(TRACKING_PARAMETER, "")

    /** The comedians of a house show, its host after them; a guest show bills its act in the title. */
    private fun artists(
        title: String,
        subtitles: List<String>
    ): List<ScrapedArtist> {
        val host = subtitles.firstNotNullOfOrNull { HOST.find(it)?.groupValues?.get(1) }?.let(::names).orEmpty()
        val comedians = subtitles.firstNotNullOfOrNull { COMEDIANS.find(it)?.groupValues?.get(1) }?.let(::names).orEmpty()
        return when {
            comedians.isNotEmpty() -> comedians.map { ScrapedArtist(it) } + host.map { ScrapedArtist(it, role = "SUPPORT") }
            host.isNotEmpty() -> host.map { ScrapedArtist(it) }
            else -> buildArtistsForEventType(title, null, EventType.COMEDY.name)
        }
    }

    private fun names(list: String): List<String> = splitSupportActs(list).filterNot(::isNonArtistName)

    private fun isCreditOrDoors(line: String): Boolean = HOST.containsMatchIn(line) || COMEDIANS.containsMatchIn(line) || DOORS.containsMatchIn(line)

    private fun readTree(json: String): JsonNode? =
        try {
            mapper.readTree(json)
        } catch (e: JacksonException) {
            logger.warn(e) { "Quatsch Comedy Club calendar answer is not parseable JSON" }
            null
        }

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()

        val SETTINGS = Regex("""var\s+eventim_ajax_object\s*=\s*(\{.*?\});""", RegexOption.DOT_MATCHES_ALL)

        /** The club's own name opens every house show's title; the venue is shown beside it anyway. */
        val HOUSE_PREFIX = Regex("""^Quatsch Comedy Club Berlin\s*-\s*""")
        val HOST = Regex("""^Moderiert von:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val COMEDIANS = Regex("""^mit:\s*(.+)$""", RegexOption.IGNORE_CASE)
        val TRACKING_PARAMETER = Regex("""[?&]srsltid=[^&#]*""")

        const val AD_TEXT_FIELDS = 5

        /** The open mic runs in the bar in the circle, which the club names in the title. */
        const val BAR_ROOM = "BAR92"

        const val SHOP_URL = "https://quatsch-comedy-club.eventim-inhouse.de/webshop/webticket/shop?event="
        const val CALENDAR_URL = "https://quatsch-comedy-club.de/tickets/"
    }
}

private val CALENDAR_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM/dd/yyyy")

/** A doors line, in German, English or Spanish, with an optional twelve-hour suffix. */
private val DOORS = Regex("""\b(?:Einlass|Entrance|Admisión)\s*:?\s*(\d{1,2})[.:](\d{2})\s*(?:Uhr)?\s*([ap]\.?\s?m\.?)?""", RegexOption.IGNORE_CASE)

private const val NOON = 12

private fun doorsTime(line: String): LocalTime? =
    DOORS.find(line)?.destructured?.let { (hour, minute, meridiem) ->
        val pm = meridiem.startsWith("p", ignoreCase = true) && hour.toInt() < NOON
        LocalTime.of(hour.toInt() + if (pm) NOON else 0, minute.toInt())
    }

/** `event_time` is a string on most shows and a one-element array on some guest shows. */
private fun startTime(node: JsonNode): LocalTime? =
    (if (node.isArray) node.firstOrNull() else node)?.asString("")?.let { time ->
        try {
            LocalTime.parse(time.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

/** The field's HTML as text lines, one per paragraph. */
private fun JsonNode.lines(field: String): List<String> =
    stringOrNull(field)
        ?.let { html ->
            Jsoup
                .parseBodyFragment(html)
                .body()
                .select("p")
                .map { it.text() }
                .ifEmpty { listOf(Jsoup.parse(html).text()) }
        }.orEmpty()
        .mapNotNull { it.blankToNull() }

private fun JsonNode.text(field: String): String? = stringOrNull(field)?.let(::decodeHtmlEntities).blankToNull()

private fun parseDate(value: String): LocalDate? =
    try {
        LocalDate.parse(value, CALENDAR_DATE)
    } catch (_: DateTimeParseException) {
        null
    }
