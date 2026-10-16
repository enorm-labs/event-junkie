package de.norm.events.scraper.kuehlspot

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for the events section of Kühlspot's one-page Elementor site. Each night is a child
 * container of `#events`: an `h2` date (`Thursday, 8 October 2026 (doors: 19:30 | start: 20:00)`),
 * an `h2` title, and a text block that lists the musicians as `Name – instrument`. A block dated
 * with a range (`20 – 25 October 2026`) announces a series whose nights follow on their own, so
 * it is skipped. The intro states the house doors, start and admission, which a night's heading
 * may override.
 */
class KuehlspotOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val intro = document.selectFirst("#events .elementor-widget-text-editor")?.text().orEmpty()
        val house =
            HouseRules(
                parseTime(HOUSE_DOORS.find(intro)?.groupValues?.get(1)),
                parseTime(HOUSE_START.find(intro)?.groupValues?.get(1)),
                HOUSE_ADMISSION
                    .find(intro)
                    ?.groupValues
                    ?.get(1)
                    ?.trim()
            )
        val nights = document.select("#events .e-con.e-child:has(h2.elementor-heading-title)")
        logger.info { "Found ${nights.size} Kühlspot block(s) on $baseUrl" }
        return nights.mapSkippingFailures(logger, "Kühlspot night") { parseNight(it, baseUrl, house) }
    }

    private fun parseNight(
        block: Element,
        baseUrl: String,
        house: HouseRules
    ): ScrapedEvent? {
        val headings = block.select("h2.elementor-heading-title").map { it.text().replace(WHITESPACE, " ").trim() }
        val dateLine = headings.first()
        val date = DATE.find(dateLine)?.value?.let(::parseDate)
        val title = headings.getOrNull(1)
        if (date == null || title.isNullOrBlank()) {
            logger.debug { "Kühlspot block '$dateLine' names no single night, skipping" }
            return null
        }
        val lines = block.select(".elementor-widget-text-editor p").flatMap(::lines)
        val musicians = lines.mapNotNull(::musician)
        val promoter = lines.firstNotNullOfOrNull { PROMOTER.find(it)?.groupValues?.get(1) }
        val description =
            lines
                .filterNot { musician(it) != null || PROMOTER.containsMatchIn(it) || it.endsWith(':') }
                .joinToString("\n")
                .blankToNull()
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = inferConcertVenueType(title),
            typeIsFallback = true,
            eventDate = date,
            doorsTime = labelledClock(dateLine, DOORS_LABELS) ?: house.doors,
            startTime = labelledClock(dateLine, START_LABELS) ?: house.start,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.KUEHLSPOT.sourceIdPrefix}$date",
            priceNote = house.admission,
            artists = if (musicians.isNotEmpty()) musicians.distinct().map { ScrapedArtist(name = it) } else headlinersFromTitle(title),
            promoters = listOfNotNull(promoter)
        )
    }

    /** The name of a `Name – instrument` line, or null when the line is prose or a set heading. */
    private fun musician(line: String): String? {
        val name =
            MUSICIAN
                .matchEntire(line)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: return null
        return name.takeIf { ':' !in it && it.none(Char::isDigit) && it.split(' ').size <= MAX_NAME_WORDS }
    }

    /** The lines of a paragraph, which the editor breaks with `<br>`. */
    private fun lines(paragraph: Element): List<String> {
        val lines = mutableListOf(StringBuilder())
        paragraph.childNodes().forEach { node ->
            when {
                node is Element && node.tagName() == "br" -> lines += StringBuilder()
                node is TextNode -> lines.last().append(node.text())
                node is Element -> lines.last().append(node.text())
            }
        }
        return lines.map { it.toString().replace(WHITESPACE, " ").trim() }.filter { it.isNotEmpty() }
    }

    private fun parseDate(text: String): LocalDate? =
        try {
            LocalDate.parse(text, DATE_FORMATTER)
        } catch (_: DateTimeParseException) {
            null
        }

    private data class HouseRules(
        val doors: LocalTime?,
        val start: LocalTime?,
        val admission: String?
    )

    private companion object {
        /** `Thursday, 8 October 2026`; a range such as `8–10 October 2026` has no weekday and does not match. */
        val DATE = Regex("""^\p{L}+day,\s+\d{1,2}\s+\p{L}+\s+\d{4}""")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

        /** `Ingebrigt Håker Flaten – double bass`, `Nicola Hein -guitar`; the dash inside `Hui-Chun` has no space beside it. */
        val MUSICIAN = Regex("""^(.+?)(?:\s[–-]|[–-]\s)\s*\p{Ll}.*$""")
        const val MAX_NAME_WORDS = 5

        /** `Eigenveranstaltung vom Jazzkeller 69 e.V.`: a night another association runs. */
        val PROMOTER = Regex("""Eigenveranstaltung (?:vom|von|der|des)\s+(.+?)(?:\s+e\.\s?V\.)?\s*$""")

        val HOUSE_DOORS = Regex("""Doors open at (\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        val HOUSE_START = Regex("""music starts at (\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        val HOUSE_ADMISSION = Regex("""Admission:\s*(.+?)\s+or as otherwise noted""", RegexOption.IGNORE_CASE)
    }
}
