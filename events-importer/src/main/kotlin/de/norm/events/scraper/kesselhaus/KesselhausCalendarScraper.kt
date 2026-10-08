package de.norm.events.scraper.kesselhaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeParseException

/**
 * Pure parser for the Kesselhaus calendar, an Angular app that server-renders five months per page.
 *
 * `/de/calendar?part=2026-10` shows August to December. Each card is a `div.item[data-id]` with the
 * category label and the cover. Everything else is in the page's `serverApp-state` transfer state, one
 * `meta:base` (room, start in UTC, tickets, notes, topics) and one `meta:de` (title, pretitle, subtitle)
 * document per event. The event's own page adds the text, as `text` blocks in its `body:de` document.
 * Past cards are dropped here, sparing their event pages.
 */
class KesselhausCalendarScraper(
    private val room: KesselhausRoom,
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val state = TransferState.of(document)
        if (state == null) {
            logger.warn { "Kesselhaus calendar page has no transfer state, skipping it" }
            return emptyList()
        }
        val today = LocalDate.now(clock)
        val cards = document.select(CARD).distinctBy { it.attr("data-id") }
        return cards
            .mapSkippingFailures(logger, "Kesselhaus calendar card") { card -> parseCard(card, state, baseUrl) }
            .filterNot { it.eventDate.isBefore(today) }
    }

    /**
     * The page after [document]: the window centred three months past its last month with a card.
     * Null once a page shows no card at all, which is where the programme ends.
     */
    fun nextPage(
        document: Document,
        url: String
    ): String? {
        val last =
            document
                .select(CARD)
                .mapNotNull { runCatching { YearMonth.parse(it.attr("data-part")) }.getOrNull() }
                .maxOrNull() ?: return null
        return "${url.substringBefore('?')}?part=${last.plusMonths(WINDOW_STEP_MONTHS)}"
    }

    /** The event's own text and the doors time it states, with the image from the page's JSON-LD; null when it has neither. */
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val id = event.sourceUrl.substringAfterLast('/')
        val texts =
            TransferState
                .of(document)
                ?.bodyTexts(id)
                .orEmpty()
                .mapNotNull(::plainText)
        val image = document.jsonLdEvents().firstNotNullOfOrNull { it.schemaImageUrl() }
        if (texts.isEmpty() && image == null) return null
        val text = texts.joinToString("\n\n").ifBlank { null }
        val doors = labelledClock(text, DOORS_LABELS)?.takeIf { start -> event.startTime?.let { start < it } == true }
        return event.copy(
            description = text ?: event.description,
            imageUrl = image ?: event.imageUrl,
            doorsTime = doors ?: event.doorsTime
        )
    }

    @Suppress("ReturnCount") // Guard clauses for the room filter and the required fields are clearer than nesting.
    private fun parseCard(
        card: Element,
        state: TransferState,
        baseUrl: String
    ): ScrapedEvent? {
        val id = card.attr("data-id")
        val base = state.meta(id, "base") ?: return null
        val venueSlug = base.stringOrNull("venue")?.substringAfterLast("//")
        if (venueSlug !in room.venueSlugs || base.path("active").asBoolean(true).not()) return null
        val text = state.meta(id, "de")
        val title = text?.stringOrNull("title")
        val start = base.stringOrNull("start")?.let(::berlinTime)
        if (title == null || start == null) {
            logger.warn { "Kesselhaus event $id has no title or start, skipping" }
            return null
        }
        val notes = base.path("notes").mapNotNull { it.asString("").substringAfterLast("//") }.toSet()
        val subtitle = text.stringOrNull("subtitle")
        val topics = base.path("topics").mapNotNull { it.asString("").substringAfterLast("//") }.toSet()
        val eventType = eventTypeOf(card.textAt(".category"), topics)
        val tickets = base.path("tickets").toList()
        return ScrapedEvent(
            title = title,
            subtitle = subtitle ?: text.stringOrNull("pretitle"),
            eventType = eventType,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl = card.selectFirst(".section-cover [style*=background-image]")?.attr("style")?.let(::backgroundUrl),
            sourceUrl = "${baseUrl.substringBefore("/calendar")}/calendar/$id",
            sourceId = "${room.eventSource.sourceIdPrefix}$id",
            ticketUrl = tickets.firstNotNullOfOrNull { ticket -> ticket.stringOrNull("url")?.takeIf { it.startsWith("http") } },
            pricePresale = tickets.price(PRESALE),
            priceBoxOffice = tickets.price(BOX_OFFICE),
            genre =
                topics
                    .mapNotNull { GENRE_TOPICS[it] }
                    .distinct()
                    .joinToString(", ")
                    .ifBlank { null },
            soldOut = SOLD_OUT in notes,
            status = statusOf(notes),
            statusNote = subtitle?.takeIf { RELOCATED in notes },
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /** A night filed as a concert but tagged as a party and not as a concert is a DJ night. */
    private fun eventTypeOf(
        category: String?,
        topics: Set<String>
    ): String {
        val type = mapEventType(category, CATEGORY_SYNONYMS) ?: EventType.OTHER.name
        return if (type == EventType.CONCERT.name && PARTY_TOPIC in topics && CONCERT_TOPIC !in topics) EventType.PARTY.name else type
    }

    /**
     * One text block as plain text. The CMS writes Markdown with a few HTML tags, and a block often ends in a
     * `#### Präsentiert von` heading over the sponsors' logos, which says nothing without them. Only the tags
     * it uses are removed, because a title such as `<Art of BLUE BLOOD>` is text in angle brackets.
     */
    private fun plainText(raw: String): String? =
        decodeHtmlEntities(raw.replace(LINE_BREAK, "\n").replace(MARKUP_TAG, "").replace(BOLD, ""))
            .lines()
            .dropLastWhile { it.isBlank() || HEADING.containsMatchIn(it) }
            .joinToString("\n") { it.replaceFirst(HEADING, "") }
            .trim()
            .ifBlank { null }

    private fun List<JsonNode>.price(type: String) =
        firstOrNull { it.stringOrNull("type")?.endsWith("//$type") == true }?.stringOrNull("A")?.let { parsePriceValue("$it €") }

    private fun statusOf(notes: Set<String>): String =
        when {
            CANCELLED in notes -> EventStatus.CANCELLED.name
            RELOCATED in notes -> EventStatus.RELOCATED.name
            else -> EventStatus.SCHEDULED.name
        }

    private fun berlinTime(utc: String) =
        try {
            Instant.parse(utc).atZone(BERLIN).toLocalDateTime()
        } catch (_: DateTimeParseException) {
            null
        }

    private fun backgroundUrl(style: String): String? = BACKGROUND_URL.find(style)?.groupValues?.get(1)

    /**
     * Angular's serialised transfer state, `&q;`-escaped JSON keyed by the store's document paths
     * (`store.<hash>.doc:/events//<id>/meta:base/<stamp>`).
     */
    private class TransferState(
        private val documents: Map<String, JsonNode>
    ) {
        fun meta(
            id: String,
            part: String
        ): JsonNode? = documents.entries.firstOrNull { (key, _) -> key.contains("/events//$id/meta:$part/") }?.value

        fun bodyTexts(id: String): List<String> =
            documents.entries
                .filter { (key, _) -> key.contains("/events//$id/body:de/") }
                .flatMap { (_, body) -> body.findParents("type").filter { it.path("type").asString("") == "text" } }
                .mapNotNull { it.path("data").stringOrNull("desc") }

        companion object {
            private val mapper: JsonMapper = JsonMapper.builder().build()
            private val ESCAPE = Regex("&[aqslg];")
            private val UNESCAPED = mapOf("&a;" to "&", "&q;" to "\"", "&s;" to "'", "&l;" to "<", "&g;" to ">")

            fun of(document: Document): TransferState? {
                val root = document.selectFirst("script#serverApp-state")?.data()?.let(::parse) ?: return null
                return TransferState(root.properties().associate { (key, value) -> key to if (value.isString) mapper.readTree(value.asString()) else value })
            }

            private fun parse(raw: String): JsonNode? =
                try {
                    mapper.readTree(ESCAPE.replace(raw) { UNESCAPED.getValue(it.value) })
                } catch (_: JacksonException) {
                    null
                }
        }
    }

    companion object {
        private val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")

        /** Five months per page, so a centre three past the last shown month leaves no gap. */
        private const val WINDOW_STEP_MONTHS = 3L

        /** An event card; a month's first card also has a `separator` twin with the same id and no content. */
        private const val CARD = "div.item[data-id][data-use=default]"
        private const val PARTY_TOPIC = "party"
        private const val CONCERT_TOPIC = "concerts"
        private const val PRESALE = "ticket"
        private const val BOX_OFFICE = "box-office"
        private const val SOLD_OUT = "sold-out"
        private const val CANCELLED = "cancelled"
        private const val RELOCATED = "relocated"

        private val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        private val MARKUP_TAG = Regex("""</?(?:h[1-6]|p|span|strong|em|b|i|u|div)(?:\s[^>]*)?>""", RegexOption.IGNORE_CASE)
        private val BOLD = Regex("""\*\*""")
        private val HEADING = Regex("""^#{1,6}\s+""")

        private val BACKGROUND_URL = Regex("""background-image:url\(([^)]+)\)""")

        /** The calendar's category labels beyond the shared ones. */
        private val CATEGORY_SYNONYMS =
            mapOf(
                "kinder" to EventType.SHOW.name,
                "tanz" to EventType.SHOW.name,
                "theater" to EventType.SHOW.name,
                "weitere" to EventType.OTHER.name
            )

        /** The topics that name a style; the others name a format or a highlight. */
        private val GENRE_TOPICS =
            mapOf(
                "blues" to "Blues",
                "world" to "World",
                "hip-hop" to "Hip Hop",
                "rock-and-pop" to "Rock, Pop",
                "metal" to "Metal",
                "jazz" to "Jazz"
            )
    }
}
