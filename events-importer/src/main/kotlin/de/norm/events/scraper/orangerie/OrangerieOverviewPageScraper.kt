package de.norm.events.scraper.orangerie

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Pure parser for the Orangerie Neukölln one-pager, whose `#programm` section lists every announced night.
 *
 * A schema.org `ItemList` of `Event`s carries the name, the dated start and the price, so it decides the rows.
 * The shared JSON-LD reader does not open an `ItemList`, so this one reads `itemListElement` itself. Each
 * `div.card` with the same `h3.card-title` adds the cover, the style tags and the Rausgegangen ticket link.
 */
class OrangerieOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nights = programme(document)
        logger.info { "Found ${nights.size} night(s) in the Orangerie programme JSON-LD" }
        val cards = document.select("div.card").associateBy { it.textAt("h3.card-title") }
        return nights.mapSkippingFailures(logger, "Orangerie night") { parseNight(it, cards, baseUrl) }
    }

    private fun programme(document: Document): List<JsonNode> =
        document
            .select("script[type=application/ld+json]")
            .mapNotNull { script -> parse(script.data()) }
            .filter { it.path("@type").asString("") == ITEM_LIST }
            .flatMap { list -> jsonLdEvents(list.path("itemListElement").toString()) }

    private fun parse(json: String): JsonNode? =
        try {
            mapper.readTree(json)
        } catch (_: JacksonException) {
            null
        }

    @Suppress("ReturnCount") // Guard clauses for the required name and date are clearer than nesting.
    private fun parseNight(
        node: JsonNode,
        cards: Map<String?, Element>,
        baseUrl: String
    ): ScrapedEvent? {
        val name = node.schemaName() ?: return null
        val date = node.schemaDate("startDate")
        if (date == null) {
            logger.warn { "Orangerie night '$name' has no startDate, skipping" }
            return null
        }
        val card = cards[name]
        val tags =
            card
                ?.select(".og-genres .event-tag")
                ?.map { it.text().trim() }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        val eventType = if (tags.any { it.equals(CONCERT_TAG, ignoreCase = true) } || name.startsWith(LIVE_SERIES)) EventType.CONCERT else EventType.PARTY
        val price = node.schemaOffers().firstNotNullOfOrNull { it.stringOrNull("price")?.toBigDecimalOrNull() }
        return ScrapedEvent(
            title = name,
            description = node.stringOrNull("description"),
            eventType = eventType.name,
            eventDate = date,
            startTime = node.schemaTime("startDate"),
            imageUrl =
                card
                    ?.selectFirst(".card-img[style]")
                    ?.attr("style")
                    ?.let(::backgroundUrl)
                    ?.let { resolveUrl(baseUrl, it) },
            sourceUrl = baseUrl,
            sourceId = "${EventSource.ORANGERIE_NEUKOELLN.sourceIdPrefix}$date-${SlugGenerator.slugify(name)}",
            ticketUrl = card?.selectFirst("a.og-act-link[title=Rausgegangen]")?.attr("abs:href")?.ifBlank { null },
            genre = tags.filterNot { it.equals(CONCERT_TAG, ignoreCase = true) }.joinToString(", ").ifBlank { null },
            priceBoxOffice = price?.takeIf { it.signum() > 0 },
            free = price?.signum() == 0,
            artists = artists(name, eventType)
        )
    }

    /**
     * The act after `w/` in `Naked Grapes w/ zodya`. A night without `w/` names its
     * crew or its series (`Gardens of Disco by Duma, The Collective`, `Nice Tries & Friends Vol. 6`), so nobody.
     */
    private fun artists(
        name: String,
        eventType: EventType
    ): List<ScrapedArtist> {
        val act = name.substringAfter(WITH, "").trim().ifBlank { return emptyList() }
        val role = if (eventType == EventType.CONCERT) "HEADLINER" else "DJ"
        return if (isNonArtistName(act)) emptyList() else listOf(ScrapedArtist(name = act, role = role))
    }

    private fun backgroundUrl(style: String): String? = BACKGROUND_URL.find(style)?.groupValues?.get(1)

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()
        const val ITEM_LIST = "ItemList"
        const val CONCERT_TAG = "Concert"
        const val LIVE_SERIES = "Parkside Sessions"
        const val WITH = " w/ "
        val BACKGROUND_URL = Regex("""background-image:url\(([^)]+)\)""")
    }
}
