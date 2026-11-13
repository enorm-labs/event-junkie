package de.norm.events.scraper.kuehlspot

import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.dateTitleId
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseEnglishWeekday
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure parser for the events section of Kühlspot's one-page Elementor site. Each night is a child
 * container of `#events`: an `h2` date (`Thursday, 8 October 2026 (doors: 19:30 | start: 20:00)`),
 * an `h2` title, and a text block that lists the musicians as `Name – instrument`, or names the acts in a
 * `Featuring artist A & artist B` line. A night's container can hold the next night's container, and a wrapper
 * container can hold only nested nights and no heading of its own, which is skipped. A block dated
 * with a range (`20 – 25 October 2026`) announces a series whose nights follow on their own, so
 * it is skipped. The intro states the house doors, start and admission, which a night's heading
 * may override: a labelled `doors:`/`start:` clock, or a bare `(14:30)` that is the start. A
 * matinée and an evening show can share a date, so a night is keyed by date and title. The page
 * sometimes names the wrong weekday (`Saturday, 15 November 2026`, a Sunday): the day, month and
 * year win, and the mismatch is logged.
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
        val nights = document.select(NIGHT)
        logger.info { "Found ${nights.size} Kühlspot block(s) on $baseUrl" }
        return nights.mapSkippingFailures(logger, "Kühlspot night") { parseNight(it, baseUrl, house) }
    }

    private fun parseNight(
        block: Element,
        baseUrl: String,
        house: HouseRules
    ): ScrapedEvent? {
        val headings = block.ownSelect("h2.elementor-heading-title").map { it.text().collapseWhitespace() }
        val dateLine =
            headings.firstOrNull() ?: run {
                logger.debug { "Kühlspot block holds only nested nights, skipping" }
                return null
            }
        val date = parseDate(dateLine)
        val title = headings.getOrNull(1)
        if (date == null || title.isNullOrBlank()) {
            logger.debug { "Kühlspot block '$dateLine' names no single night, skipping" }
            return null
        }
        val ownStart = labelledClock(dateLine, START_LABELS) ?: parseTime(BARE_CLOCK.find(dateLine)?.groupValues?.get(1))
        val lines = block.ownSelect(".elementor-widget-text-editor p").flatMap(::lines)
        val musicians = lines.mapNotNull(::musician) + lines.flatMap(::featured)
        val promoter = lines.firstNotNullOfOrNull { PROMOTER.find(it)?.groupValues?.get(1) }
        val description =
            lines
                .filterNot { musician(it) != null || FEATURING.matches(it) || PROMOTER.containsMatchIn(it) || LINK_POINTER.matches(it) || it.endsWith(':') }
                .joinToString("\n")
                .blankToNull()
        return ScrapedEvent(
            title = title,
            description = description,
            eventType = inferConcertVenueType(title),
            typeIsFallback = true,
            eventDate = date,
            doorsTime = labelledClock(dateLine, DOORS_LABELS) ?: house.doors.takeIf { ownStart == null },
            startTime = ownStart ?: house.start,
            sourceUrl = baseUrl,
            sourceId = EventSource.KUEHLSPOT.dateTitleId(date, title),
            priceNote = house.admission,
            artists = if (musicians.isNotEmpty()) musicians.distinct().map { ScrapedArtist(name = it) } else headlinersFromTitle(title),
            promoters = listOfNotNull(promoter)
        )
    }

    /**
     * The elements of this night alone. The page can nest the next night inside this one's container (events 13821
     * and 13822), and a plain select would read its title and text as this night's.
     */
    private fun Element.ownSelect(query: String): List<Element> = select(query).filter { it.closest(NIGHT) == this }

    /** The acts of a `Featuring artist A & artist B` line, or none. */
    private fun featured(line: String): List<String> =
        FEATURING
            .matchEntire(line)
            ?.groupValues
            ?.get(1)
            ?.split(FEATURED_SEPARATOR)
            ?.map { it.replace(ARTIST_WORD, "").trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

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
        return lines.map { it.toString().collapseWhitespace() }.filter { it.isNotEmpty() }
    }

    /** The printed day, month and year; a weekday that disagrees is logged, never used to move the date. */
    private fun parseDate(dateLine: String): LocalDate? {
        val match = DATE.find(dateLine) ?: return null
        val date = dateOrNull { LocalDate.parse(match.groupValues[2], DATE_FORMATTER) } ?: return null
        return dateCheckedAgainstWeekday(date, parseEnglishWeekday(match.groupValues[1]), dateLine, plausible = { false })
    }

    private data class HouseRules(
        val doors: LocalTime?,
        val start: LocalTime?,
        val admission: String?
    )

    private companion object {
        const val NIGHT = "#events .e-con.e-child:has(h2.elementor-heading-title)"

        /** `Thursday, 8 October 2026`; a range such as `8–10 October 2026` has no weekday and does not match. */
        val DATE = Regex("""^(\p{L}+day),\s+(\d{1,2}\s+\p{L}+\s+\d{4})""")

        /** `Saturday, 14 November 2026 (14:30)`: a clock alone in brackets, with no `doors`/`start` label. */
        val BARE_CLOCK = Regex("""\(\s*(\d{1,2}:\d{2})\s*\)""")
        val DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH)

        /** `Ingebrigt Håker Flaten – double bass`, `Nicola Hein -guitar`; the dash inside `Hui-Chun` has no space beside it. */
        val MUSICIAN = Regex("""^(.+?)(?:\s[–-]|[–-]\s)\s*\p{Ll}.*$""")
        const val MAX_NAME_WORDS = 5

        /** `Eigenveranstaltung vom Jazzkeller 69 e.V.` or `Event organized by Jazzkeller 69 e.V.`: a night another association runs. */
        val PROMOTER = Regex("""(?:Eigenveranstaltung (?:vom|von|der|des)|Event organi[sz]ed by)\s+(.+?)(?:\s+e\.\s?V\.)?\s*$""")

        /** `Featuring artist Rachel D Clarke & artist Christoph Kühl`: the acts of a night with no instrument list. */
        val FEATURING = Regex("""^featuring\s+(.+)$""", RegexOption.IGNORE_CASE)

        /**
         * `Further information >> here <<` (event 13818): the words of a link whose address the stored text loses, so they point at nothing.
         */
        val LINK_POINTER =
            Regex("""^(?:(?:further|more)\s+info(?:rmation)?|weitere\s+info(?:rmationen|s)?)[^\p{L}]*(?:here|hier)[^\p{L}]*$""", RegexOption.IGNORE_CASE)
        val FEATURED_SEPARATOR = Regex("""\s*(?:&|,|\band\b)\s*""")
        val ARTIST_WORD = Regex("""^artist\s+""", RegexOption.IGNORE_CASE)

        val HOUSE_DOORS = Regex("""Doors open at (\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        val HOUSE_START = Regex("""music starts at (\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)
        val HOUSE_ADMISSION = Regex("""Admission:\s*(.+?)\s+or as otherwise noted""", RegexOption.IGNORE_CASE)
    }
}
