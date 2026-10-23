package de.norm.events.scraper.interkosmos

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * Pure parser for the theme's `wp/v2/events` answers, which carry the event fields as top-level `ik_*` keys. The unfiltered
 * list shows almost nothing, so the importer asks twice: a date range for the one-off events, and `repetition=true` for
 * the rules.
 *
 * - **A rule is a weekday and a week of the month** (`ik_weekday`, `ik_monthweek`), monthly or weekly, with no dates. Each
 *   comedy night has two rules, one for each Thursday it plays. The rules are expanded over [RULE_HORIZON_WEEKS], and a
 *   rule carries no exceptions, so a night the house cancels still shows.
 * - **`ik_event_type` types the event and sets the scope.** Yoga is out (EVENT_SCOPE.md §3.2). The `BuchBar` readings
 *   sometimes have no type, so the title types them and names the author.
 * - `ik_url` is the ticket link, and `ik_price` a bare number such as `10`, which `parsePriceValue` does not read.
 */
class InterkosmosApiScraper {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper = JsonMapper.builder().build()

    fun scrape(
        oneOffJson: String,
        rulesJson: String,
        today: LocalDate
    ): List<ScrapedEvent> {
        val oneOffs = jsonMapper.readTree(oneOffJson).toList()
        val rules = jsonMapper.readTree(rulesJson).toList()
        val events =
            oneOffs.mapSkippingFailures(logger, "Interkosmos event") { oneOff(it) } +
                rules.mapSkippingFailures(logger, "Interkosmos rule") { occurrences(it, today) }.flatten()
        logger.info { "Found ${oneOffs.size} Interkosmos event(s) and ${rules.size} rule(s), ${events.size} dated row(s) in scope" }
        return events
    }

    private fun oneOff(node: JsonNode): ScrapedEvent? {
        val date = node.stringOrNull("ik_date")?.let(LocalDate::parse) ?: return null
        return event(node, date, idSuffix = "")
    }

    private fun occurrences(
        node: JsonNode,
        today: LocalDate
    ): List<ScrapedEvent>? {
        val weekday = node.stringOrNull("ik_weekday")?.let { DayOfWeek.valueOf(it.uppercase()) } ?: return null
        val limit = today.plusWeeks(RULE_HORIZON_WEEKS)
        val dates =
            when (node.stringOrNull("ik_repetition")) {
                "weekly" -> generateSequence(today.with(TemporalAdjusters.nextOrSame(weekday))) { it.plusWeeks(1) }.takeWhile { it <= limit }.toList()
                "monthly" -> monthlyDates(weekday, node.path("ik_monthweek").asString("").toIntOrNull() ?: return null, today, limit)
                else -> return null
            }
        return dates.mapNotNull { event(node, it, idSuffix = "-$it") }
    }

    /** A week the month lacks, the fifth Thursday of most months, has no date. */
    private fun monthlyDates(
        weekday: DayOfWeek,
        ordinal: Int,
        today: LocalDate,
        limit: LocalDate
    ): List<LocalDate> =
        generateSequence(today.withDayOfMonth(1)) { it.plusMonths(1) }
            .takeWhile { it <= limit }
            .mapNotNull { first -> first.with(TemporalAdjusters.dayOfWeekInMonth(ordinal, weekday)).takeIf { it.month == first.month } }
            .filter { it in today..limit }
            .toList()

    private fun event(
        node: JsonNode,
        date: LocalDate,
        idSuffix: String
    ): ScrapedEvent? {
        val type = node.stringOrNull("ik_event_type")
        if (type in PARTICIPATION) return null
        val title =
            node
                .path("title")
                .stringOrNull("rendered")
                ?.let(::decodeHtmlEntities)
                ?.let(::cleanEventTitle) ?: return null
        val author =
            BUCHBAR
                .matchEntire(title)
                ?.groupValues
                ?.get(1)
                ?.trim()
        val start = parseTime(node.stringOrNull("ik_time_start"))
        val end = parseTime(node.stringOrNull("ik_time_end"))?.takeIf { start != null && it.isAfter(start) }
        val price = node.stringOrNull("ik_price")
        val amount = price?.replace(',', '.')?.toBigDecimalOrNull() ?: parsePriceValue(price)
        return ScrapedEvent(
            title = title,
            description = node.path("content").stringOrNull("rendered")?.let(::descriptionOf),
            eventType = TYPES[type] ?: EventType.READING.name.takeIf { author != null } ?: EventType.OTHER.name,
            typeIsFallback = type !in TYPES && author == null,
            eventDate = date,
            startTime = start,
            endDate = end?.let { date },
            endTime = end,
            imageUrl = node.stringOrNull("ik_event_thumbnail"),
            sourceUrl = node.stringOrNull("link") ?: return null,
            sourceId = "${EventSource.INTERKOSMOS.sourceIdPrefix}${node.path("id").asLong(0).takeIf { it > 0 } ?: return null}$idSuffix",
            ticketUrl = node.stringOrNull("ik_url"),
            pricePresale = amount,
            priceNote = price?.takeIf { amount == null },
            artists = listOfNotNull(author?.blankToNull()?.let { ScrapedArtist(it) })
        )
    }

    private fun descriptionOf(html: String): String? = Jsoup.parseBodyFragment(html).select("p, div:not(:has(p, div))").paragraphText(distinct = true)

    private companion object {
        /** Three months: each comedy night plays twice a month. */
        const val RULE_HORIZON_WEEKS = 13L

        val PARTICIPATION = setOf("Yoga")
        val BUCHBAR = Regex("""BuchBar mit (.+)""")

        val TYPES =
            mapOf(
                "Comedy" to EventType.COMEDY.name,
                "Lesung" to EventType.READING.name,
                "Poetry Slam" to EventType.READING.name,
                "Spieleabend" to EventType.QUIZ.name,
                "Party" to EventType.PARTY.name,
                "Burlesque Show" to EventType.SHOW.name,
                "Konzert" to EventType.CONCERT.name
            )
    }
}
