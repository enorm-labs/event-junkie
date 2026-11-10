package de.norm.events.scraper.karakas

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractFanOutImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Karakas, a GoDaddy Website Builder site. The source URL is the home page,
 * and the run reads two pages resolved against it: `comedy-shows` and `pub-quiz-1`, whose page
 * decides the event type. A failed page is skipped with a warning and leaves the run incomplete.
 *
 * The comedy calendar is the main list. The page renders only its first few nights, so the run
 * fetches the page's published scripts until one carries the calendar's props
 * ([KarakasCalendarScraper]). A page that shows the calendar but whose scripts do not carry it
 * fails, so a changed publish format cannot pass as an empty programme. A tour card on a
 * calendar night with the same title is the same show, and the calendar's entry wins.
 *
 * The home page's own announcement and the live music and karaoke page are not read.
 *
 * @see <a href="https://karakasbar.com/comedy-shows">Karakas comedy shows</a>
 */
@Component
class KarakasWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractFanOutImporter<Pair<String, EventType>>() {
    override val eventSource: EventSource = EventSource.KARAKAS
    override val listsWholeProgramme: Boolean = true
    override val noInputReadMessage: String = "No Karakas programme page could be read"

    private val pageScraper = KarakasPageScraper(clock)
    private val calendarScraper = KarakasCalendarScraper()

    override suspend fun inputs(url: String): List<Pair<String, EventType>> = PROGRAMME_PAGES.map { (path, type) -> resolveUrl(url, path) to type }

    override suspend fun read(input: Pair<String, EventType>): List<ScrapedEvent> = readPage(input.first, input.second)

    override fun failure(input: Pair<String, EventType>): String = "Failed to read Karakas page ${input.first}, skipping"

    override fun doneLine(
        events: Int,
        inputs: Int
    ): String = "Scraped $events Karakas event(s) from $inputs page(s)"

    private suspend fun readPage(
        pageUrl: String,
        type: EventType
    ): List<ScrapedEvent> {
        val document = htmlFetcher.fetchDocument(pageUrl)
        val scripts = pageScraper.calendarScripts(document)
        val calendar = if (scripts.isEmpty()) emptyList() else readCalendar(scripts, pageUrl, type)
        return (calendar + pageScraper.scrape(document, pageUrl, type)).distinctBy { it.eventDate to it.title.lowercase() }
    }

    private suspend fun readCalendar(
        scripts: List<String>,
        pageUrl: String,
        type: EventType
    ): List<ScrapedEvent> {
        for (script in scripts) {
            calendarScraper.scrape(htmlFetcher.fetchHtml(script), pageUrl, type)?.let { return it }
        }
        error("No published script of $pageUrl carries the Karakas calendar")
    }

    private companion object {
        val PROGRAMME_PAGES = listOf("comedy-shows" to EventType.COMEDY, "pub-quiz-1" to EventType.QUIZ)
    }
}

val KARAKAS_LIMITATIONS =
    VenueLimitations(
        EventSource.KARAKAS,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the shows have no page of their own, so each points at its programme page", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a show is billed by its title, and its comedians appear only in prose", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no producer beside a show's title", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.GENRE, "the bar names no genre", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price; each show links to an outside ticket shop", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar prints a start and an end, no doors time", verified = "2026-10-21"),
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "a tour card or a quiz announcement prints only the date; the time is on the outside ticket shop",
            verified = "2026-10-21"
        ),
        AcceptedLimitation(
            LimitedAspect.IMAGE,
            "the calendar sets no image; only the tour cards and the quiz announcements carry one",
            verified = "2026-10-21"
        ),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-21"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status, so a cancelled show can only disappear", verified = "2026-10-21")
    )
