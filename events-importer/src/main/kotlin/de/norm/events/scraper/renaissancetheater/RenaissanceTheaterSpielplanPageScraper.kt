package de.norm.events.scraper.renaissancetheater

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTitleStatus
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for the Renaissance-Theater Spielplan (`/spielplan/`), one WordPress page that
 * runs about a year ahead. Each `div.grid` under `main` holds one month's `div.rt-spielplan-day`
 * blocks, and the `div.rt-headline` before it names the month. The heading prints the year only
 * from the next calendar year on, so a month without one takes it from the day's weekday.
 *
 * A `div.rt-sp-date` is one performance: the clock (`19.30`), the production link, the title in
 * its `h4` with a performance note (`Erste Voraufführung`) in a `span`, a subtitle `p`, and badges
 * in `div.specials`. The badges hold the room (`Im Foyer`) and either a marker (`Premiere`) or the
 * format (`Lesung`, `Konzert`). Only the first performance of a run carries its badge, so the type
 * reads the badges of every performance of the production. The ticket link is per production, so
 * the production slug, the date and the clock key the row.
 *
 * A night that names no format is one of the house's plays. Its `SHOW` is the venue's word, not a
 * fallback, so the comedian lookup cannot retype Dominique Horwitz's Faust as comedy.
 */
class RenaissanceTheaterSpielplanPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("main div.grid div.rt-spielplan-day div.rt-sp-date")
        logger.info { "Found ${rows.size} Renaissance-Theater performance(s) on $baseUrl" }
        val (outOfScope, shows) = rows.partition(::isOutOfScope)
        if (outOfScope.isNotEmpty()) logger.info { "Skipping ${outOfScope.size} Renaissance-Theater tour(s) or children's show(s): out of scope" }
        val formats = shows.groupBy(::productionUrl).mapValues { (_, performances) -> performances.flatMap(::formatBadges).distinct() }
        return shows.mapSkippingFailures(logger, "Renaissance-Theater performance") { row -> parseRow(row, formats[productionUrl(row)].orEmpty()) }
    }

    private fun productionUrl(row: Element): String? = row.selectFirst(".rt-sp-teaser a[href]")?.absUrl("href")?.blankToNull()

    /** The badge beside the room: a marker such as `Premiere`, or the format, which only the first performance may carry. */
    private fun formatBadges(row: Element): List<String> = row.select(".specials > .special-premiere").mapNotNull { it.text().blankToNull() }

    private fun parseRow(
        row: Element,
        formats: List<String>
    ): ScrapedEvent? {
        val link = row.selectFirst(".rt-sp-teaser a[href]") ?: return null
        val url = productionUrl(row) ?: return null
        val slug = url.trimEnd('/').substringAfterLast('/').blankToNull() ?: return null
        val heading = link.selectFirst("h4") ?: return null
        val title = heading.ownText().replace(WHITESPACE, " ").blankToNull() ?: return null
        val eventDate = dateOf(row) ?: return null
        val startTime = row.textAt(".rt-sp-time")?.let(::parseClock)
        val note =
            heading
                .selectFirst("span")
                ?.text()
                ?.trim('(', ')', ' ')
                ?.blankToNull()
        val teaser = row.textAt(".rt-sp-teaser > p")
        return ScrapedEvent(
            title = title,
            subtitle = listOfNotNull(teaser, note).joinToString(" · ").blankToNull(),
            eventType = renaissanceEventType(title, teaser, formats) ?: EventType.SHOW.name,
            eventDate = eventDate,
            startTime = startTime,
            room = FOYER.takeIf { row.selectFirst(".specials .special-foyer") != null },
            sourceUrl = url,
            sourceId = "${EventSource.RENAISSANCE_THEATER.sourceIdPrefix}$slug-$eventDate-${startTime?.toString()?.replace(":", "") ?: "0000"}",
            ticketUrl = row.selectFirst(".specials a.special-tickets[href]")?.absUrl("href")?.blankToNull(),
            status = note?.let(::parseTitleStatus) ?: EventStatus.SCHEDULED.name,
            artists = renaissanceActs(title, teaser)
        )
    }

    /** The day block's date: its month heading's month and year, or the year the weekday names. */
    private fun dateOf(row: Element): LocalDate? {
        val day = row.closest("div.rt-spielplan-day") ?: return null
        val dayOfMonth = day.textAt(".rt-sp-day")?.toIntOrNull() ?: return null
        val headline = day.closest("div.grid")?.previousElementSiblings()?.firstOrNull { it.hasClass("rt-headline") } ?: return null
        val month = GERMAN_MONTH_NAMES.indexOfFirst { it.equals(headline.textAt("h1"), ignoreCase = true) }.takeIf { it >= 0 } ?: return null
        val year = headline.textAt(".rt-year-number")?.filter(Char::isDigit)?.toIntOrNull()
        val monthDay = runCatching { MonthDay.of(month + 1, dayOfMonth) }.getOrNull() ?: return null
        return year?.let { monthDay.atYear(it) } ?: inferYearForWeekday(monthDay, parseGermanWeekdayAbbreviation(day.textAt(".rt-sp-wday")), clock)
    }

    private fun parseClock(text: String): LocalTime? =
        CLOCK.matchEntire(text)?.destructured?.let { (hour, minute) -> runCatching { LocalTime.of(hour.toInt(), minute.toInt()) }.getOrNull() }

    /** A guided tour of the house (EVENT_SCOPE.md §3.2) or a show for small children (§3.5). */
    private fun isOutOfScope(row: Element): Boolean {
        val title = row.textAt(".rt-sp-teaser h4").orEmpty()
        return TOUR.containsMatchIn(title) || billsChildrensShow(title, row.textAt(".rt-sp-teaser > p"))
    }

    private companion object {
        val CLOCK = Regex("""(\d{1,2})[.:](\d{2})""")
        val TOUR = Regex("""\bführung(?:en)?\b""", RegexOption.IGNORE_CASE)

        /** `Im Foyer` means the Bruckner-Foyer, the house's second room. */
        const val FOYER = "Bruckner-Foyer"
    }
}

/**
 * The type the title, the subtitle and the badges name, or null when none does: a reading, a talk
 * or a slam, then Kabarett, then a play, then a concert, then any other staged show. A reading
 * comes first because a `Konzertlesung` is one; a play before a concert because a `Songdrama` is one.
 */
internal fun renaissanceEventType(
    title: String,
    subtitle: String?,
    badges: List<String>
): String? {
    val text = (listOf(title, subtitle.orEmpty()) + badges).joinToString(" ")
    return when {
        READING.containsMatchIn(text) -> EventType.READING.name
        COMEDY.containsMatchIn(text) -> EventType.COMEDY.name
        PLAY.containsMatchIn(text) -> EventType.SHOW.name
        CONCERT.containsMatchIn(text) -> EventType.CONCERT.name
        SHOW.containsMatchIn(text) -> EventType.SHOW.name
        else -> null
    }
}

/**
 * The performers a row names: the reader or singer who opens the title or the subtitle (`Christian
 * Brückner liest …`), and the names after a closing `mit` in the subtitle (`Sonderkonzert mit
 * Vasiliki Roussi`). Only a run of capitalised names counts, so `mit kleinem Orchester` and
 * `mit Florian Illies‘ großartiger „Chronik …“` bill nobody.
 */
internal fun renaissanceActs(
    title: String,
    subtitle: String?
): List<ScrapedArtist> {
    val openers = listOfNotNull(title, subtitle).mapNotNull { PERFORMER_OPENS.find(it)?.groupValues?.get(1) }
    val guests = subtitle?.let { GUESTS_CLOSE.find(it)?.groupValues?.get(1) }?.let(::namesOrNull).orEmpty()
    return (openers.filter(PERSON_NAME::matches) + guests)
        .filterNot(::isNonArtistName)
        .distinct()
        .map { ScrapedArtist(name = it, titleDerived = true) }
}

/** The names of a `A, B und C` list, or null when any part is not a name. */
private fun namesOrNull(list: String): List<String>? {
    val names = list.split(',').flatMap(::splitSegmentOnConjunctions).mapNotNull { it.blankToNull() }
    return names.takeIf { it.isNotEmpty() && it.all(PERSON_NAME::matches) }
}

private val READING =
    Regex("""lesung|leseperformance|\bliest\b|gelesen|buchpremiere|matinee|\bim dialog\b|\bslam\b""", RegexOption.IGNORE_CASE)
private val COMEDY = Regex("""kabarett|comedy""", RegexOption.IGNORE_CASE)
private val PLAY = Regex("""drama|theaterabend|schauspiel|komödie|theaterstück""", RegexOption.IGNORE_CASE)
private val CONCERT = Regex("""konzert|liederabend|record release|\bsingt\b|\bsingen\b""", RegexOption.IGNORE_CASE)
private val SHOW = Regex("""show|revue|gala|musical""", RegexOption.IGNORE_CASE)

private val PERFORMER_OPENS = Regex("""^(.+?)\s+(?:liest|singt|präsentiert)\b""")
private val GUESTS_CLOSE = Regex("""(?:\bmit|gelesen von):?\s+([^:]+)$""")

/** Two to four capitalised words, a particle such as `von` allowed between them. */
private val PERSON_NAME = Regex("""\p{Lu}[\p{L}.'’-]+(?:\s+(?:(?:von|van|de|di|zu)\s+)?\p{Lu}[\p{L}.'’-]+){1,3}""")
