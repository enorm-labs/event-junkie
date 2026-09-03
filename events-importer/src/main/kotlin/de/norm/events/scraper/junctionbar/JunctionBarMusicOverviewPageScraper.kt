package de.norm.events.scraper.junctionbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.resolveUrl
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import java.time.DateTimeException
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for a single Junction Bar monthly live-music program page
 * (e.g. `program/07_2026/07_26.html`).
 *
 * The page is retro hand-coded HTML with a **flat** layout: inside `div.gridContainer`,
 * an event starts at a *date bar* — a `<div>` holding a `<table>` whose `<td>` carries a
 * `strong.datum` with a German `DD.MM.` date (plus the weekday and, in a `strong.musikstil`,
 * either the genre or a "two/three acts tonight" marker). Everything between one date bar and
 * the next belongs to that night: one or more band blocks, each a `.Stil1222` name, a
 * `p.text` bio, artist links, a band photo, and a shared `junction-bar-shop.de` ticket link.
 * A night with no real band (only a "PRIVAT PARTY" placeholder) is skipped.
 *
 * Dates render year-less, but the four-digit year is taken from the page URL's month folder
 * (`.../07_2026/...`), so no weekday inference is needed. Default show times follow the venue's
 * SHOWTIMES rule (Fri & Sat 22:00, otherwise 21:00) unless the date bar carries an explicit
 * `HH:mm`. The venue leaves recently-passed nights on the page; those are dropped centrally at
 * persistence time (`EventUpsertService`), so this parser returns every dated night as-is.
 *
 * @see JunctionBarMusicWebsiteImporter for the fetch orchestration (listing → monthly pages).
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the inline markup needs several small field extractors.
class JunctionBarMusicOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all live-music events from one monthly program page.
     *
     * @param baseUrl the URL the document was fetched from, used to derive the year, resolve
     *   relative image paths, and as each event's `sourceUrl`.
     * @return one [ScrapedEvent] per dated night that lists at least one real band.
     */
    @Suppress("ReturnCount") // Guard clauses for a missing year and a missing container are clearer than nesting.
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val year =
            YEAR_IN_URL
                .find(baseUrl)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: run {
                    logger.warn { "No MM_YYYY month folder in Junction Bar URL '$baseUrl', skipping" }
                    return emptyList()
                }
        val container = document.selectFirst("div.gridContainer") ?: document.body()

        val groups = segmentIntoNights(container, ::isDateBar)
        logger.info { "Found ${groups.size} dated night(s) on Junction Bar page $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed nights without aborting the whole import.
        return groups.mapNotNull { group ->
            try {
                parseNight(group, year, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Junction Bar night, skipping" }
                null
            }
        }
    }

    /** A date-bar child carries a `strong.datum` whose text holds a `DD.MM.` date. */
    internal fun isDateBar(child: Element): Boolean = child.selectFirst("strong.datum") != null && DATE_PATTERN.containsMatchIn(child.text())

    @Suppress("ReturnCount") // Guard clauses for the required date and band list are clearer than nesting.
    private fun parseNight(
        night: Night,
        year: Int,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate = parseDate(night.dateBar, year) ?: return null

        val bands = parseBands(night.content)
        if (bands.isEmpty()) return null // "PRIVAT PARTY" or an empty night — nothing to import.

        val title = bands.joinToString(TITLE_SEPARATOR)
        val ticketUrl =
            night.content
                .select("a[href*=junction-bar-shop.de]")
                .firstOrNull()
                ?.attr("href")
                ?.blankToNull()

        return ScrapedEvent(
            title = title,
            description = parseDescription(night.content),
            eventType = EventType.CONCERT.name,
            eventDate = eventDate,
            startTime = parseStartTime(night.dateBar, eventDate),
            imageUrl = parseImageUrl(night.content, baseUrl),
            sourceUrl = baseUrl,
            sourceId = buildSourceId(ticketUrl, eventDate, title),
            ticketUrl = ticketUrl,
            genre = parseGenre(night.dateBar, night.content),
            artists =
                bands
                    .filterNot { isNonArtistName(it) }
                    .map { ScrapedArtist(name = it, role = "HEADLINER") }
        )
    }

    /** The `DD.MM.` date from the date bar, combined with the [year] taken from the page URL. */
    private fun parseDate(
        dateBar: Element,
        year: Int
    ): LocalDate? {
        val match = DATE_PATTERN.find(dateBar.text()) ?: return null
        val (day, month) = match.destructured
        return try {
            LocalDate.of(year, month.toInt(), day.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /**
     * Band name(s) for the night. Each `.Stil1222` element holds one act name; the nested
     * `.two_bands_musikstil` genre span is removed first, and empty shells (a genre-only
     * wrapper span) and "PRIVAT PARTY" placeholders are dropped.
     */
    private fun parseBands(content: Elements): List<String> =
        content
            .select(".Stil1222")
            .map { el ->
                el
                    .clone()
                    .also { it.select(".two_bands_musikstil").remove() }
                    .text()
                    .trim()
            }.filter { it.isNotBlank() && !it.equals(PRIVAT_PARTY, ignoreCase = true) }
            .distinct()

    /** The band bios (`p.text`), joined into one description. */
    private fun parseDescription(content: Elements): String? =
        content
            .select("p.text")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n\n")
            .takeIf { it.isNotBlank() }

    /**
     * The show time: an explicit `HH:mm` in the date bar wins; otherwise the venue's default
     * (Fri & Sat 22:00, every other day 21:00), derived from the resolved date's weekday.
     */
    private fun parseStartTime(
        dateBar: Element,
        eventDate: LocalDate
    ): LocalTime {
        val explicit =
            TIME_PATTERN.find(dateBar.text())?.let { m ->
                val (h, min) = m.destructured
                runCatching { LocalTime.of(h.toInt(), min.toInt()) }.getOrNull()
            }
        if (explicit != null) return explicit
        val isWeekendNight = eventDate.dayOfWeek == DayOfWeek.FRIDAY || eventDate.dayOfWeek == DayOfWeek.SATURDAY
        return if (isWeekendNight) WEEKEND_SHOWTIME else WEEKDAY_SHOWTIME
    }

    /** The first real band photo (excluding logos, decorative bars, and the ticket button), absolute. */
    private fun parseImageUrl(
        content: Elements,
        baseUrl: String
    ): String? =
        content
            .select("img")
            .map { it.attr("src").trim() }
            .firstOrNull { src -> src.isNotBlank() && DECORATIVE_IMAGE_MARKERS.none { it in src } }
            ?.let { resolveUrl(baseUrl, it) }

    /**
     * The event genre: the date bar's `strong.musikstil` when it names a style (not a
     * "two/three acts tonight" marker), otherwise the first band's `.two_bands_musikstil` tag.
     */
    private fun parseGenre(
        dateBar: Element,
        content: Elements
    ): String? {
        val fromDateBar =
            dateBar
                .select("strong.musikstil")
                .map { it.text().trim() }
                .firstOrNull { it.isNotBlank() && !ACTS_TONIGHT.containsMatchIn(it) }
        if (fromDateBar != null) return fromDateBar
        return content.select(".two_bands_musikstil").map { it.text().trim() }.firstOrNull { it.isNotBlank() }
    }

    /**
     * A stable `sourceId`: the ticket-shop page slug (the event's canonical identity, e.g.
     * `funkverband`), falling back to date + slugified title when a night has no ticket link.
     */
    private fun buildSourceId(
        ticketUrl: String?,
        eventDate: LocalDate,
        title: String
    ): String {
        val ticketSlug =
            ticketUrl
                ?.substringAfterLast('/')
                ?.substringBeforeLast('.')
                ?.takeIf { it.isNotBlank() }
        val identity = ticketSlug ?: "$eventDate-${SlugGenerator.slugify(title)}"
        return "${EventSource.JUNCTION_BAR.sourceIdPrefix}$identity"
    }

    companion object {
        /** The `DD.MM.` date opening a date bar (the trailing dot after the month distinguishes it from a time). */
        private val DATE_PATTERN = Regex("""(\d{1,2})\.(\d{1,2})\.""")

        /** An explicit `HH:mm` show time in a date bar (e.g. the "---- 20:30 ----" override). */
        private val TIME_PATTERN = Regex("""(\d{1,2}):(\d{2})""")

        /** The `MM_YYYY` month folder in a program URL, capturing the four-digit year. */
        private val YEAR_IN_URL = Regex("""/\d{2}_(\d{4})/""")

        /** The "two/three acts tonight" multi-act marker that shares the `musikstil` slot with real genres. */
        private val ACTS_TONIGHT = Regex("""acts?\s+tonight""", RegexOption.IGNORE_CASE)

        /** Filename fragments identifying non-content images (logo, decorative bars, ticket button, scroll arrow). */
        private val DECORATIVE_IMAGE_MARKERS =
            listOf("tickets-button", "balken_lang", "scrollup", "junction-bar-logo")

        private const val PRIVAT_PARTY = "PRIVAT PARTY"
        private const val TITLE_SEPARATOR = " + "
        private val WEEKDAY_SHOWTIME = LocalTime.of(21, 0)
        private val WEEKEND_SHOWTIME = LocalTime.of(22, 0)
    }
}

/**
 * Splits the flat program into nights: each date bar starts a new group, and the children
 * following it (until the next date bar) are that night's content.
 *
 * The venue lays both programme pages out flat — the nights are siblings, not containers — so
 * [isDateBar] is what tells one apart, and it differs per page.
 */
internal fun segmentIntoNights(
    container: Element,
    isDateBar: (Element) -> Boolean
): List<Night> {
    val nights = mutableListOf<Night>()
    var current: Night? = null
    for (child in container.children()) {
        if (isDateBar(child)) {
            current = Night(dateBar = child, content = Elements())
            nights.add(current)
        } else {
            current?.content?.add(child)
        }
    }
    return nights
}

/** One night's date bar plus the content blocks that follow it until the next date bar. */
internal data class Night(
    val dateBar: Element,
    val content: Elements
)
