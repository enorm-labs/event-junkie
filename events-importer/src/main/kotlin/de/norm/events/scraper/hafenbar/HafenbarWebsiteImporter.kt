package de.norm.events.scraper.hafenbar

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.readEach
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for the Hafenbar, a WordPress (Avada) site with no event list. Its programme is
 * two weekly party pages, the Friday Schlagerparty and the Saturday Kaptains Club Party, resolved
 * against the source URL, the home page. Each page announces only its next night
 * ([HafenbarPartyPageScraper]), so a run stores at most two nights. Nothing is projected forward:
 * the venue publishes no rule for the weeks after.
 *
 * The REST API lists both pages, but `/wp-json/wp/v2/pages/<id>` answers 500 when it renders the
 * content, so the HTML pages are read. A failed page is skipped with a warning and leaves the run
 * incomplete. The news posts that announce one-off specials are not read.
 *
 * @see <a href="https://www.hafenbar-berlin.de/schlager-party-berlin/">Hafenbar Schlagerparty</a>
 */
@Component
class HafenbarWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.HAFENBAR
    override val fetchesBeyondEntryPage: Boolean = true
    override val listsWholeProgramme: Boolean = true

    private val scraper = HafenbarPartyPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val pages = PARTY_PAGES.map { resolveUrl(url, it) }
        val read = readEach(pages, { "Failed to read Hafenbar party page $it, skipping" }) { scraper.scrape(htmlFetcher.fetchDocument(it), it) }
        check(read.complete || read.items.isNotEmpty()) { "No Hafenbar party page could be read" }
        logger.info { "Scraped ${read.items.size} Hafenbar night(s) from ${pages.size} weekly party page(s)" }
        return ImportResult.Success(events = read.items, etag = null, lastModified = null, complete = read.complete)
    }

    private companion object {
        val PARTY_PAGES = listOf("schlager-party-berlin/", "kaptains-club-party-berlin/")
    }
}

val HAFENBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.HAFENBAR,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each page prints one opening time per night"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the pages print no end time"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the announcement carries no poster, only the page's decoration and gallery"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the pages link no ticket shop"),
        AcceptedLimitation(LimitedAspect.PRICE, "the pages print no entry price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the pages credit no promoter beside the Hafenbar team"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the pages state no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the pages have no status, so a cancelled night can only disappear"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "each weekly party has one page that shows only its next night, so every night of a series points at that page"
        )
    )
