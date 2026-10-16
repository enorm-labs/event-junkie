package de.norm.events.scraper.bkatheater

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for the BKA Theater Spielplan (`/spielplan/`), one server-rendered page that
 * runs about a year ahead. Each `div.bka-spielplan-row` is one performance:
 *
 * ```
 * <div class="… bka-spielplan-row event-wrapper" data-pid="462" data-tid="4218">
 *   <span class="event-date" data-eventdate="20261016">16.10.</span> <span class="event-time" data-eventtime="2000">
 *   <a class="event-title" href="/prg/462?tid=4218"><h2>Friedemann Weise:</h2><h3>Satire suchen ein Zuhause</h3></a>
 *   <span class="badge bka-badge" data-badge="Comedy">
 * ```
 *
 * The full date sits only in `data-eventdate`, as the row prints `16.10.`. `data-tid` is the
 * performance and keys it; `data-pid` is the production, whose page all its performances share. A
 * `bka-spielfrei` row marks a dark evening. The red status dot, `bka-color-rot`, is a sold-out night.
 *
 * The page's JSON-LD repeats the next 30 performances with the shop price, matched by date, clock and title.
 */
class BkaTheaterSpielplanPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.bka-spielplan-row.event-wrapper:not(.bka-spielfrei)")
        logger.info { "Found ${rows.size} BKA Theater performance(s) on $baseUrl" }
        val (childrens, shows) = rows.partition(::billsChildrensRow)
        if (childrens.isNotEmpty()) logger.info { "Skipping ${childrens.size} BKA Theater children's show(s): out of scope" }
        val prices = shopPrices(document)
        return shows.mapSkippingFailures(logger, "BKA Theater performance") { row -> parseRow(row, prices) }
    }

    /** A Kindertheater row, by its title, its badges and its note (EVENT_SCOPE.md §3.5). */
    private fun billsChildrensRow(row: Element): Boolean =
        billsChildrensShow(row.textAt("a.event-title").orEmpty(), (badgesOf(row) + listOfNotNull(row.textAt(".description"))).joinToString(" "))

    private fun badgesOf(row: Element): List<String> = row.select(".bka-badge[data-badge]").mapNotNull { it.attr("data-badge").blankToNull() }

    private fun parseRow(
        row: Element,
        prices: Map<String, BigDecimal>
    ): ScrapedEvent? {
        val tid = row.attr("data-tid").blankToNull() ?: return null
        val eventDate = row.selectFirst("[data-eventdate]")?.attr("data-eventdate")?.let(::parseCompactDate) ?: return null
        val heading = row.textAt("a.event-title h2") ?: return null
        val programme = row.textAt("a.event-title h3")
        val act = heading.removeSuffix(":").trim().takeIf { heading.endsWith(":") && programme != null }
        val title = if (act != null) "$act: $programme" else heading.removeSuffix(":").trim()
        val badges = badgesOf(row)
        val note = row.textAt(".description")?.takeUnless { PREMIERE_MARKER.matches(it) }
        val startTime = row.selectFirst("[data-eventtime]")?.attr("data-eventtime")?.let { parseTime(it, HHMM) }
        val eventType = bkaEventType(badges)
        return ScrapedEvent(
            title = title,
            subtitle = note,
            eventType = eventType ?: EventType.SHOW.name,
            typeIsFallback = eventType == null,
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = row.selectFirst("img.bka-spielplan-img[src]")?.absUrl("src")?.blankToNull(),
            sourceUrl = row.selectFirst("a.event-title[href]")?.absUrl("href")?.substringBefore('?') ?: return null,
            sourceId = "${EventSource.BKA_THEATER.sourceIdPrefix}$tid",
            ticketUrl = row.selectFirst("a.bka-spielplan-btn[title^=Tickets][href]")?.absUrl("href"),
            genre = badges.filterNot { NON_GENRE_BADGE.matches(it) }.joinToString(", ").ifEmpty { null },
            pricePresale = prices[priceKey(eventDate, startTime, title)],
            soldOut = row.selectFirst(".bka-color-rot") != null,
            artists = bkaActs(act, programme, title, eventType)
        )
    }

    /** The JSON-LD offer price of each performance it lists, keyed like [priceKey]. */
    private fun shopPrices(document: Document): Map<String, BigDecimal> =
        document
            .jsonLdEvents()
            .mapNotNull { node ->
                val name = node.schemaName() ?: return@mapNotNull null
                val date = node.schemaDate("startDate") ?: return@mapNotNull null
                val price = node.schemaOffers().firstNotNullOfOrNull { it.stringOrNull("price")?.toBigDecimalOrNull() }
                price?.let { priceKey(date, node.schemaTime("startDate"), name) to it }
            }.toMap()

    private fun priceKey(
        date: LocalDate,
        time: LocalTime?,
        title: String
    ): String = "$date $time ${title.replace(WHITESPACE, " ").trim().lowercase()}"

    private fun parseCompactDate(value: String): LocalDate? = runCatching { LocalDate.parse(value, YYYYMMDD) }.getOrNull()

    private companion object {
        val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        /** A note that marks the performance, not the show: `Premiere`, `WELTPREMIERE`. */
        val PREMIERE_MARKER = Regex("""(?:welt|berlin)?premiere""", RegexOption.IGNORE_CASE)

        /** Badges that mark the performance or its broadcast, not the kind of show. */
        val NON_GENRE_BADGE = Regex("""(?:welt|berlin)?premiere|live & stream""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The type the badges name, or null when none does: a concert, then comedy and Kabarett, then a
 * reading or a talk, then a staged show. The badges are the house's own formats (`Neuköllnical`,
 * `Voll-Playback-Theater`), so they match by word stem.
 */
internal fun bkaEventType(badges: List<String>): String? {
    val text = badges.joinToString(" ").lowercase()
    return when {
        "konzert" in text -> EventType.CONCERT.name
        COMEDY_STEMS.any { it in text } -> EventType.COMEDY.name
        READING_STEMS.any { it in text } -> EventType.READING.name
        SHOW_STEMS.any { it in text } -> EventType.SHOW.name
        else -> null
    }
}

/**
 * The acts a row bills. The heading names them before its colon (`Rachel Intervention & Yvonne
 * Nightstand:`), except for a house series, whose programme line names the act. A heading with no
 * colon names a production, and bills nobody unless the night is a concert. A `präsentiert` names
 * the presenting act, and `& Friends` or `und Band` its unnamed company.
 */
internal fun bkaActs(
    act: String?,
    programme: String?,
    title: String,
    eventType: String?
): List<ScrapedArtist> {
    val names =
        when {
            act != null && act.lowercase() in HOUSE_SERIES -> programme
            act != null -> act
            eventType == EventType.CONCERT.name && title.lowercase() !in HOUSE_SERIES -> title
            else -> null
        } ?: return emptyList()
    return names
        .replace(PRESENTS, "")
        .replace(UNNAMED_COMPANY, "")
        .split(VERSUS)
        .flatMap(::splitSegmentOnConjunctions)
        .mapNotNull { it.blankToNull() }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, titleDerived = true) }
}

/** Series the house runs under its own name: the act stands in the programme line. */
private val HOUSE_SERIES = setOf("unerhörte musik", "intervention berlin")

private val PRESENTS = Regex("""\s+präsentiert$""", RegexOption.IGNORE_CASE)
private val UNNAMED_COMPANY = Regex("""\s+(?:&|und)\s+(?:friends|band)$""", RegexOption.IGNORE_CASE)
private val VERSUS = Regex("""\s+(?:versus|vs\.?)\s+""", RegexOption.IGNORE_CASE)

private val COMEDY_STEMS = listOf("comedy", "komedy", "kabarett", "kasperett", "impro", "stand-up", "satir")
private val READING_STEMS = listOf("lesung", "leseshow", "talk")
private val SHOW_STEMS = listOf("show", "revue", "theater", "drag", "musical", "neuköllnical", "puppen", "performance")
