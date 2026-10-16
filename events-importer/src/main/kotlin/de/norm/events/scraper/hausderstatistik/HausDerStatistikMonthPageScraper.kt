package de.norm.events.scraper.hausderstatistik

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.endOn
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Parser for one Haus der Statistik month page (`/programm/?typ=veranstaltung&monat[]=2026-10`).
 * Each `.teaser.event` row carries an ISO `time[datetime]`, the time span, the title, a teaser
 * and the venue's format tags. The tags type the row, and a row whose every tag is a
 * participation format is dropped ([isParticipationFormat]).
 */
class HausDerStatistikMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select(".listing .teaser.event")
        logger.info { "Found ${rows.size} programme row(s) on Haus der Statistik month page $baseUrl" }
        return rows.mapSkippingFailures(logger, "Haus der Statistik programme row") { parseRow(it) }
    }

    private fun parseRow(row: Element): ScrapedEvent? {
        val tags = row.select(".button-group a.button").map { it.text() }.filter { it.isNotBlank() }
        val title = row.textAt(".teaser__header .title")
        val url = row.hrefAt("a[rel=bookmark]")
        val dateTime = row.attrAt("time[datetime]", "datetime")
        val eventDate = dateTime?.let(::parseIsoDate)
        if (title == null || url == null || eventDate == null) {
            logger.warn { "Haus der Statistik row without title, link or date ('$title', $url, '$dateTime'), skipping it" }
            return null
        }
        if (isParticipationFormat(tags)) return null

        val startTime = parseClockPrefix(dateTime.substringAfter('T', ""))
        val endTime = parseTime(row.textAt(".teaser__meta span")?.substringAfter('–', "")?.trim())
        val eventType = tags.firstNotNullOfOrNull { mapEventType(it, TAG_TYPES) }
        return ScrapedEvent(
            title = title,
            description = row.textAt(".teaser__content"),
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            endDate = endTime?.let { endOn(eventDate, startTime, it) },
            endTime = endTime,
            imageUrl = row.imgSrcAt(".teaser__figure img"),
            sourceUrl = url,
            sourceId = "${EventSource.HAUS_DER_STATISTIK.sourceIdPrefix}${url.trimEnd('/').substringAfterLast('/')}",
            artists = if (eventType == EventType.CONCERT.name) buildArtistsForEventType(title, null, eventType) else emptyList()
        )
    }

    companion object {
        /** The venue's format tags that name an evening on a stage; the base table covers `Lesung`, `Konzert` and the like. */
        private val TAG_TYPES =
            mapOf(
                "musik" to EventType.CONCERT.name,
                "live-act" to EventType.CONCERT.name,
                "vortrag" to EventType.READING.name,
                "diskussion" to EventType.READING.name
            )

        /**
         * Tags of formats a visitor takes part in. `Diskussion` is among them because the venue
         * files its regular discussion rounds there; a talk also carries `Vortrag`.
         */
        private val PARTICIPATION_TAGS = setOf("workshop", "werkstatt", "treffen", "kochen", "diskussion")

        /**
         * Whether every format tag of a row names a participation format (EVENT_SCOPE.md §3.2): an
         * open workshop, a garden meeting, a club's weekly get-together. A row without tags stays.
         */
        fun isParticipationFormat(tags: List<String>): Boolean = tags.isNotEmpty() && tags.all { it.lowercase() in PARTICIPATION_TAGS }
    }
}
