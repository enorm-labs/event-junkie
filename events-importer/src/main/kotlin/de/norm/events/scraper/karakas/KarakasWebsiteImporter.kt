package de.norm.events.scraper.karakas

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.readEach
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
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
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KARAKAS
    override val fetchesBeyondEntryPage: Boolean = true
    override val listsWholeProgramme: Boolean = true

    private val pageScraper = KarakasPageScraper(clock)
    private val calendarScraper = KarakasCalendarScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val pages = PROGRAMME_PAGES.map { (path, type) -> resolveUrl(url, path) to type }
        val read = readEach(pages, { "Failed to read Karakas page ${it.first}, skipping" }) { (page, type) -> readPage(page, type) }
        check(read.complete || read.items.isNotEmpty()) { "No Karakas programme page could be read" }
        logger.info { "Scraped ${read.items.size} Karakas event(s) from ${pages.size} page(s)" }
        return ImportResult.Success(events = read.items, etag = null, lastModified = null, complete = read.complete)
    }

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
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the shows have no page of their own, so each points at its programme page"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a show is billed by its title, and its comedians appear only in prose"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names no producer beside a show's title"),
        AcceptedLimitation(LimitedAspect.GENRE, "the bar names no genre"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no price; each show links to an outside ticket shop"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar prints a start and an end, no doors time"),
        AcceptedLimitation(LimitedAspect.START_TIME, "a tour card or a quiz announcement prints only the date; the time is on the outside ticket shop"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the calendar sets no image; only the tour cards and the quiz announcements carry one"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status, so a cancelled show can only disappear")
    )
