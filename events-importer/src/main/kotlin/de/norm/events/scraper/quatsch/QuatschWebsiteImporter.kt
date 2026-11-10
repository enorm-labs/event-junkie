package de.norm.events.scraper.quatsch

import de.norm.events.event.SpokenLanguage
import de.norm.events.scraper.AbstractFanOutImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
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
) : AbstractFanOutImporter<QuatschWebsiteImporter.CalendarDay>() {
    override val eventSource: EventSource = EventSource.QUATSCH

    private val scraper = QuatschCalendarScraper()

    override suspend fun inputs(url: String): List<CalendarDay> {
        val calendar = checkNotNull(scraper.calendar(htmlFetcher.fetchDocument(url))) { "Quatsch Comedy Club tickets page carries no calendar settings" }
        return calendar.days.take(MAX_DAYS).map { CalendarDay(calendar, it) }
    }

    override suspend fun read(input: CalendarDay): List<ScrapedEvent> {
        val (calendar, day) = input
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

    override fun failure(input: CalendarDay): String = "Failed to read the Quatsch Comedy Club calendar for ${input.day}, skipping it"

    override fun combine(events: List<ScrapedEvent>): List<ScrapedEvent> = events.distinctBy { it.sourceId }

    override fun doneLine(
        events: Int,
        inputs: Int
    ): String = "Scraped $events Quatsch Comedy Club show(s) from $inputs calendar day(s)"

    /** One day the calendar names, with the settings its request carries. */
    data class CalendarDay(
        val calendar: QuatschCalendar,
        val day: LocalDate
    )

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
        AcceptedLimitation(LimitedAspect.END_TIME, "the calendar states no end", verified = "2026-10-03"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices appear only in the box-office shop, per seat category", verified = "2026-10-03"),
        AcceptedLimitation(LimitedAspect.GENRE, "the calendar names no genre", verified = "2026-10-03"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the calendar names no producer of a guest show", verified = "2026-10-03"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the calendar marks no show as sold out", verified = "2026-10-04"),
        houseLanguage = SpokenLanguage.GERMAN
    )
