package de.norm.events.scraper.quatsch

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.readEach
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * Website importer for the Quatsch Comedy Club under the Friedrichstadt-Palast, a WordPress site
 * whose calendar is an Eventim box-office plugin.
 *
 * `/tickets/` renders only today's shows. The rest come from `wp-admin/admin-ajax.php`, one POST
 * per day (`action=eventim_calendar`), as the page's own arrows ask for them. The page prints the
 * nonce and every day with a show, about 65 days over five months, so the walk asks only for those.
 * A failed day is logged and skipped, and leaves the run incomplete.
 *
 * The club has no page per show. A house show links its box-office shop page, a guest show its
 * outside ticket seller.
 *
 * @see QuatschCalendarScraper for the field mapping.
 * @see <a href="https://quatsch-comedy-club.de/tickets/">Quatsch Comedy Club tickets</a>
 */
@Component
class QuatschWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.QUATSCH
    override val fetchesBeyondEntryPage: Boolean = true

    private val scraper = QuatschCalendarScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val calendar = checkNotNull(scraper.calendar(htmlFetcher.fetchDocument(url))) { "Quatsch Comedy Club tickets page carries no calendar settings" }
        val days = calendar.days.take(MAX_DAYS)
        val answers = readEach(days, { "Failed to read the Quatsch Comedy Club calendar for $it, skipping it" }) { day -> readDay(calendar, day) }
        val events = answers.items.distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} Quatsch Comedy Club show(s) from ${days.size} calendar day(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = answers.complete)
    }

    private suspend fun readDay(
        calendar: QuatschCalendar,
        day: LocalDate
    ): List<ScrapedEvent> {
        val form =
            mapOf(
                "action" to "eventim_calendar",
                "security" to calendar.nonce,
                "date" to "${day.dayOfMonth}.${day.monthValue}.${day.year}",
                "format" to "arrows",
                "language" to "de",
                "location" to calendar.city
            )
        return scraper.scrapeDay(htmlFetcher.postForm(calendar.ajaxUrl, form))
    }

    private companion object {
        /** A runaway guard; the calendar lists about 65 days. */
        const val MAX_DAYS = 200
    }
}

/**
 * German is the house language because the programme is German in practice (#2584): on `2026-10-04` the
 * production API listed 87 upcoming shows, and 10 of the 11 descriptions were German. The English one
 * says "Language: English", and that phrase wins over the default.
 */
val QUATSCH_LIMITATIONS =
    VenueLimitations(
        EventSource.QUATSCH,
        AcceptedLimitation(LimitedAspect.END_TIME, "the calendar states no end"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only in the box-office shop, per seat category"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no genre"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the calendar names no producer of a guest show"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the calendar marks no show as sold out"),
        houseLanguage = SpokenLanguage.GERMAN
    )
