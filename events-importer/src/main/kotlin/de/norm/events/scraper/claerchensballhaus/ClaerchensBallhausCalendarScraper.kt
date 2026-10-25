package de.norm.events.scraper.claerchensballhaus

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.balancedJsonAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Pie Calendar on Clärchens Ballhaus' `/programm/`. The page writes every entry, past ones included,
 * into the FullCalendar options as `eventSources: [[{title, start, details, permalink, customLink}, …]]`, a JavaScript
 * literal. A recurring night arrives as one entry per occurrence, all with the same `permalink`, so the key adds the start.
 * The `end` is not a real end: it is midnight, often of the first occurrence's day.
 *
 * There is no category. The text under the title names the format: a beginners' class followed by a `Tanzabend` or a
 * `Milonga` is a dance night, so a party. History talks, workshops, the wine dinner and the YesChoir! sing-along are out of
 * scope. A children's concert is marked `for-children`.
 */
class ClaerchensBallhausCalendarScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val script = document.select("script").map { it.data() }.firstOrNull { EVENT_SOURCES in it }
        val sources = script?.let { balancedJsonAt(it, it.indexOf(EVENT_SOURCES) + EVENT_SOURCES.length) }
        if (sources == null) {
            logger.warn { "No eventSources in the Clärchens Ballhaus calendar at $baseUrl" }
            return emptyList()
        }
        val entries = jsonMapper.readTree(sources).flatMap { it.toList() }
        val events = entries.mapSkippingFailures(logger, "Clärchens Ballhaus calendar entry") { toEvent(it) }
        logger.info { "Found ${entries.size} Clärchens Ballhaus calendar entr(ies), ${events.size} in scope" }
        return events
    }

    private fun toEvent(entry: JsonNode): ScrapedEvent? {
        val title = entry.stringOrNull("title")?.let(::decodeHtmlEntities) ?: return null
        val permalink = entry.stringOrNull("permalink") ?: return null
        val start = LocalDateTime.parse(entry.stringOrNull("start") ?: return null)
        val details = entry.stringOrNull("details")
        val text = htmlParagraphText(details)
        if (isOutOfScope(title, permalink, text)) return null

        val type = typeOf(title, text)
        val name = title.replace(CONCERT_SUFFIX, "")
        return ScrapedEvent(
            title = name,
            description = text,
            eventType = type,
            typeIsFallback = type == EventType.OTHER.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl =
                details?.let {
                    Jsoup
                        .parse(it)
                        .selectFirst("img[src]")
                        ?.attr("src")
                        .blankToNull()
                },
            sourceUrl = permalink,
            sourceId = "${EventSource.CLAERCHENS_BALLHAUS.sourceIdPrefix}${start.format(KEY_TIME)}-${slugOf(permalink)}",
            ticketUrl = entry.stringOrNull("customLink")?.takeIf { TICKET_SHOP.containsMatchIn(it) },
            artists = if (type == EventType.CONCERT.name && !HOUSE_SERIES.containsMatchIn(name)) actsOf(name, type) else emptyList()
        ).forChildrenIf(childrensShowPhrase(title, text))
    }

    /** `Susanna Hurrell Soprano – Songs By Candlelight` bills the singer, not her voice type. */
    private fun actsOf(
        title: String,
        type: String
    ) = buildArtistsForEventType(title, null, type).map { it.copy(name = it.name.replace(VOICE_TYPE, "")) }

    private fun isOutOfScope(
        title: String,
        permalink: String,
        text: String?
    ): Boolean = OUT_OF_SCOPE.containsMatchIn(title) || "/tour-" in permalink || SING_ALONG.containsMatchIn(text.orEmpty())

    private fun typeOf(
        title: String,
        text: String?
    ): String {
        val words = "$title ${text.orEmpty()}"
        return when {
            READING.containsMatchIn(words) -> EventType.READING.name
            CONCERT.containsMatchIn(words) -> EventType.CONCERT.name
            DANCE_NIGHT.containsMatchIn(words) -> EventType.PARTY.name
            else -> EventType.OTHER.name
        }
    }

    private fun slugOf(permalink: String): String = URI.create(permalink).path.trim('/')
}

private const val EVENT_SOURCES = "eventSources: "
private val KEY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm")
private val CONCERT_SUFFIX = Regex("""\s+[–-]\s+Konzert$""")
private val VOICE_TYPE = Regex("""\s+(?:Soprano|Sopran|Mezzosopran|Tenor|Bariton|Baritone)$""")

private val OUT_OF_SCOPE = Regex("""Vortrag|Führung|Workshop|Weinmahl|Dinner|Menü""", RegexOption.IGNORE_CASE)

/** The audience is the choir: `Singt in dreistimmiger Harmonie und werdet Teil des … Konzerts`. */
private val SING_ALONG = Regex("""werdet Teil|Mitsing|Sing-?along""", RegexOption.IGNORE_CASE)
private val READING = Regex("""\bSlam\b|\bSalon\b|\bLesung\b""", RegexOption.IGNORE_CASE)
private val CONCERT = Regex("""Konzert|Kammermusik|Soprano|Tenors|Choir""", RegexOption.IGNORE_CASE)
private val DANCE_NIGHT = Regex("""Tanzabend|Milonga|Übungsstunde|\bParty\b""", RegexOption.IGNORE_CASE)

/** The house's own concert series, the children's and family ones included, which name no act in the title. */
private val HOUSE_SERIES = Regex("""Sonntagskonzert|Kinderkonzert|Familienkonzert|^DSO\b""", RegexOption.IGNORE_CASE)

/** `customLink` is a ticket shop, or the organiser's or a course's own site, which is no ticket link. */
private val TICKET_SHOP = Regex("""tickettailor\.|yesticket\.|eventbrite\.|rausgegangen\.|sevn\.ly|eventim\.|reservix\.|pretix\.""")
