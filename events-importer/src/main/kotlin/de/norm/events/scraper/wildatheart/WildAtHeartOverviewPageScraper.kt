package de.norm.events.scraper.wildatheart

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

/**
 * Pure HTML parser for Wild at Heart's retro `concerts.php` programme page.
 *
 * The site is a hand-coded frameset (`wah.htm`); the concert programme lives on a
 * single `/concerts.php` page reached from the `topics.htm` nav frame. Every event is
 * a `<tr>` row carrying a leading `.datum` date cell (`Weekday DD.MM.`, **no year**),
 * a `.band` headliner and any number of `.supportband` acts (each tagged with a
 * `.stil-country` `(Genre - Country)` label), an optional `.dj`, a flyer image under
 * `/uploads/img/…`, and an optional `.headlines` banner that may embed a
 * `Tickets:<url>` presale link, a `Beginn HH:MM` start time, or an `Eintritt frei`
 * free-entry note. There are no per-event URLs — the whole programme is one page.
 *
 * Dates carry a weekday but no year, so the year is inferred from the weekday via
 * [inferYearForWeekday]: among nearby candidate years the one whose `DD.MM.` actually
 * lands on the stated weekday and falls closest to today wins. The venue leaves
 * recently-passed events listed; those are dropped centrally at persistence time
 * (`EventUpsertService`), so this parser returns every dated row as-is.
 *
 * @see WildAtHeartWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.wildatheartberlin.de/concerts.php">Wild at Heart programme</a>
 */
@Suppress("TooManyFunctions") // Cohesive single-responsibility parser; the retro markup needs many small field extractors
class WildAtHeartOverviewPageScraper(
    /** Clock for year inference. Defaults to the system clock; override in tests for determinism. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page document.
     *
     * @param baseUrl the URL the document was fetched from, used as each event's `sourceUrl`.
     * @return a list of [ScrapedEvent] instances, one per dated `<tr>` row.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("tr:has(.datum)")
        logger.info { "Found ${rows.size} event row(s) on Wild at Heart programme" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed rows without aborting the whole import
        return rows.mapNotNull { row ->
            try {
                parseRow(row, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Wild at Heart event row, skipping" }
                null
            }
        }
    }

    /** Parses one event `<tr>` row into a [ScrapedEvent]. */
    @Suppress("ReturnCount") // Guard clauses for the required date/title are clearer than nesting
    private fun parseRow(
        row: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val eventDate =
            parseDatum(row.textAt(".datum")) ?: run {
                logger.warn { "Could not parse Wild at Heart date from '${row.textAt(".datum")}', skipping row" }
                return null
            }

        // The headliner is the `.band`; a bandless row (e.g. a flea market) falls back to its banner headline.
        val bandName = row.selectFirst(".band")?.text()?.let(::stripQuotes)
        val headline = row.textAt(".headlines")
        val title = (bandName ?: headline)?.takeIf { it.isNotBlank() }
        if (title == null) {
            logger.warn { "Wild at Heart event on $eventDate has no title, skipping" }
            return null
        }

        val genre = parseGenre(row.textAt(".stil-country"))
        val supportNames = row.select(".supportband").map { stripQuotes(it.text()) }.filter { it.isNotBlank() }
        val djNames = row.select(".dj").map { it.text().trim() }.filter { it.isNotBlank() }

        // A `.headlines` banner only decorates a real event (band present); when it *is* the title
        // (bandless row) it is not repeated as a subtitle.
        val subtitle = if (bandName != null) cleanBanner(headline) else null
        val description = row.select(".beschreibung").joinToString("\n") { it.text().trim() }.takeIf { it.isNotBlank() }

        val ticketUrl = headline?.let { TICKETS_PATTERN.find(it)?.groupValues?.get(1) }
        val startTime = headline?.let { parseBannerStart(it) }
        val free = headline?.contains("eintritt frei", ignoreCase = true) == true

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = description,
            // No category field on this retro page; infer from the title (concert by default for this
            // live-music venue, flipping only on an unambiguous keyword — e.g. a "Flohmarkt" → OTHER).
            eventType = inferConcertVenueType(title),
            eventDate = eventDate,
            startTime = startTime,
            imageUrl = row.selectFirst("img[src]")?.absUrl("src")?.takeIf { it.isNotBlank() },
            // No per-event URLs on this single-page site — the programme page is the source.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.WILD_AT_HEART.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            ticketUrl = ticketUrl,
            genre = genre,
            free = free,
            artists = buildArtists(bandName, supportNames, djNames)
        )
    }

    /**
     * Builds the lineup: the `.band` headliner(s) first, then support acts and DJs in listing order.
     *
     * A bandless row (title came from the banner) contributes no headliner — its title is an event
     * name, not a performer. Non-artist labels (placeholders, "+Guest", segment labels) are dropped.
     */
    private fun buildArtists(
        bandName: String?,
        supportNames: List<String>,
        djNames: List<String>
    ): List<ScrapedArtist> =
        buildList {
            if (bandName != null) addAll(headlinersFromTitle(bandName))
            supportNames.filterNot { isNonArtistName(it) }.forEach { add(ScrapedArtist(name = it, role = "SUPPORT")) }
            djNames.filterNot { isNonArtistName(it) }.forEach { add(ScrapedArtist(name = it, role = "DJ")) }
        }

    /**
     * Parses a `.datum` cell like "Mi 15.07." into a [LocalDate].
     *
     * The weekday abbreviation and `DD.MM.` day/month come from the text; the year is
     * [inferred][inferYearForWeekday] from the weekday. Returns `null` when the text
     * carries no parseable date.
     */
    @Suppress("ReturnCount") // Null-safe early exits for each date component are clearer than nested let-chains
    private fun parseDatum(text: String?): LocalDate? {
        if (text == null) return null
        val match = DATUM_PATTERN.find(text) ?: return null
        val (weekdayAbbrev, day, month) = match.destructured
        val weekday = parseGermanWeekdayAbbreviation(weekdayAbbrev)
        val monthDay = runCatching { MonthDay.of(month.toInt(), day.toInt()) }.getOrNull() ?: return null
        return inferYearForWeekday(monthDay, weekday, clock)
    }

    /**
     * Extracts the genre from a `.stil-country` label like "(Punk - USA)" → "Punk".
     *
     * The label packs a music style and a country/origin as "(Style - Country)". Only the
     * style is kept (the part before the " - " separator). An empty "( - )" label yields `null`.
     */
    private fun parseGenre(stilCountry: String?): String? {
        if (stilCountry == null) return null
        val inner = stilCountry.trim().removeSurrounding("(", ")").trim()
        return inner
            .substringBefore(" - ")
            .trim()
            .trim('-')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    /**
     * The start time a `.headlines` banner states — "Beginn 21:00", or the flea market's "ab 14 Uhr"
     * (#1142). `null` when the banner names no time, which most rows do not (see
     * [WILD_AT_HEART_LIMITATIONS]).
     */
    private fun parseBannerStart(headline: String): LocalTime? =
        BEGINN_PATTERN
            .find(headline)
            ?.groupValues
            ?.get(1)
            ?.let { parseTime(it) }
            ?: AB_UHR_PATTERN.find(headline)?.let { match ->
                val minute = match.groupValues[2].toIntOrNull() ?: 0
                runCatching { LocalTime.of(match.groupValues[1].toInt(), minute) }.getOrNull()
            }

    /** Strips the decorative surrounding double-quotes and whitespace a band/support name is wrapped in. */
    private fun stripQuotes(text: String): String = text.trim().trim('"').trim()

    /** Cleans a `.headlines` banner for use as a subtitle — drops any embedded `Tickets:<url>` link. */
    private fun cleanBanner(headline: String?): String? = headline?.replace(TICKETS_PATTERN, "")?.trim()?.takeIf { it.isNotBlank() }

    companion object {
        /** Matches a `.datum` cell "Weekday DD.MM.", capturing the German weekday abbreviation, day and month. */
        private val DATUM_PATTERN = Regex("""(Mo|Di|Mi|Do|Fr|Sa|So)\s*(\d{1,2})\.(\d{1,2})\.""", RegexOption.IGNORE_CASE)

        /** Extracts a `Tickets:<url>` presale link embedded in a `.headlines` banner. */
        private val TICKETS_PATTERN = Regex("""Tickets:\s*(https?://\S+)""", RegexOption.IGNORE_CASE)

        /** Extracts a "Beginn HH:MM" start time from a `.headlines` banner. */
        private val BEGINN_PATTERN = Regex("""Beginn\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE)

        /** Extracts an "ab HH Uhr" / "ab HH:MM Uhr" start time from a `.headlines` banner. */
        private val AB_UHR_PATTERN = Regex("""\bab\s+(\d{1,2})(?:[:.](\d{2}))?\s*Uhr\b""", RegexOption.IGNORE_CASE)
    }
}
