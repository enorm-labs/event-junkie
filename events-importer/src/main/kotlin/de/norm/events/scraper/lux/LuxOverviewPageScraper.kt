package de.norm.events.scraper.lux

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure parser for the L.U.X home page. Each night is a `main.dates > section`: a `.when` cell
 * (`Do<br>15.10.`, no year, so the weekday picks it), the party name in `h2`, a tagline in `h3`
 * and free text whose bold runs are the DJs. A line that is only a clock is the start, or the
 * doors when it reads `Tür`. A private party ("Geschlossene Gesellschaft") is skipped.
 */
class LuxOverviewPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val nights = document.select("main.dates > section")
        logger.info { "Found ${nights.size} night(s) on the L.U.X page" }
        return nights.mapSkippingFailures(logger, "an L.U.X night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        night: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val heading =
            night
                .selectFirst(".when")
                ?.brLines()
                ?.joinToString(" ")
                .orEmpty()
        val date = parseDate(heading)
        if (date == null) {
            logger.warn { "L.U.X night with date cell '$heading' has no readable date, skipping" }
            return null
        }
        val title = night.flatTextAt(".details h2")
        if (title == null) {
            logger.warn { "L.U.X night on $date has no title, skipping" }
            return null
        }
        val subtitle = night.flatTextAt(".details h3")
        if (PRIVATE_PARTY.containsMatchIn("$title $subtitle")) {
            logger.debug { "L.U.X night on $date is a private party ('$title'), skipping" }
            return null
        }
        val paragraphs = night.select(".details > p")
        val priceNote =
            paragraphs
                .lastOrNull { PRICE_LINE.containsMatchIn(it.text()) }
                ?.text()
                ?.clean()
                .blankToNull()
        val lines = paragraphs.filterNot { it.text().clean() == priceNote }.flatMap { it.brLines() }
        val clock = lines.firstNotNullOfOrNull { TIME_LINE.matchEntire(it) }
        val time = clock?.let { parseTime("${it.groupValues[HOUR_GROUP].padStart(2, '0')}:${it.groupValues[MINUTE_GROUP]}") }
        val isDoors = !clock?.groupValues?.get(DOORS_GROUP).isNullOrEmpty()
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = lines.filterNot { TIME_LINE.matches(it) }.joinToString("\n").blankToNull(),
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            doorsTime = time.takeIf { isDoors },
            startTime = time.takeUnless { isDoors },
            imageUrl = night.selectFirst(".bild a.ltbx[href]")?.absUrl("href").blankToNull(),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.LUX.sourceIdPrefix}$date",
            priceNote = priceNote,
            free = detectFree(priceNote = priceNote),
            artists = djs(night).map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    private fun parseDate(heading: String): LocalDate? {
        val (weekday, day, month) = DATE_CELL.matchEntire(heading)?.destructured ?: return null
        return try {
            inferYearForWeekday(MonthDay.of(month.toInt(), day.toInt()), parseGermanWeekdayAbbreviation(weekday), clock)
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Every bold line in the night's text that is not a clock, once each. */
    private fun djs(night: Element): List<String> =
        night
            .select(".details > p strong")
            .flatMap { it.brLines() }
            .filterNot { TIME_LINE.matches(it) || isNonArtistName(it) }
            .distinct()

    private fun Element.flatTextAt(selector: String): String? = selectFirst(selector)?.text()?.clean().blankToNull()

    /** The element's text split at every `<br>`, nested ones included, each line flattened, blank ones dropped. */
    private fun Element.brLines(): List<String> {
        val copy = clone()
        copy.select("br").forEach { it.replaceWith(TextNode(LINE_BREAK)) }
        return copy.text().split(LINE_BREAK).mapNotNull { it.clean().blankToNull() }
    }

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        /** `Do 15.10.`, the `.when` cell with its line break flattened. */
        val DATE_CELL = Regex("""^(\p{L}{2})\.?\s+(\d{1,2})\.(\d{1,2})\.?$""")

        /** `21.00 Uhr`, `Tür 21.00 Uhr`, `21.00 - Late`: a line that holds only the night's clock. */
        val TIME_LINE = Regex("""^(?:(tür|einlass)\s*:?\s*)?(?:ab\s+)?(\d{1,2})[.:](\d{2})(?:\s*uhr)?(?:\s*[-–].*)?$""", RegexOption.IGNORE_CASE)
        const val DOORS_GROUP = 1
        const val HOUR_GROUP = 2
        const val MINUTE_GROUP = 3

        /** A private-use character, so the whitespace collapsing in `text()` keeps it. */
        const val LINE_BREAK = "\uE000"

        val PRICE_LINE = Regex("""eintritt|spende|€|euro""", RegexOption.IGNORE_CASE)
        val PRIVATE_PARTY = Regex("""geschlossene\s+gesellschaft|privat[-\s]*party""", RegexOption.IGNORE_CASE)
    }
}
