package de.norm.events.scraper.hafenbar

import de.norm.events.scraper.AbstractFanOutImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.resolveUrl
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
) : AbstractFanOutImporter<String>() {
    override val eventSource: EventSource = EventSource.HAFENBAR
    override val listsWholeProgramme: Boolean = true
    override val noInputReadMessage: String = "No Hafenbar party page could be read"

    private val scraper = HafenbarPartyPageScraper()

    override suspend fun inputs(url: String): List<String> = PARTY_PAGES.map { resolveUrl(url, it) }

    override suspend fun read(input: String): List<ScrapedEvent> = scraper.scrape(htmlFetcher.fetchDocument(input), input)

    override fun failure(input: String): String = "Failed to read Hafenbar party page $input, skipping"

    override fun doneLine(
        events: Int,
        inputs: Int
    ): String = "Scraped $events Hafenbar night(s) from $inputs weekly party page(s)"

    private companion object {
        val PARTY_PAGES = listOf("schlager-party-berlin/", "kaptains-club-party-berlin/")
    }
}

val HAFENBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.HAFENBAR,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "each page prints one opening time per night", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the pages print no end time", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the announcement carries no poster, only the page's decoration and gallery", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the pages link no ticket shop", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PRICE, "the pages print no entry price", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the pages credit no promoter beside the Hafenbar team", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the pages state no ticket status", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the pages have no status, so a cancelled night can only disappear", verified = "2026-10-17"),
        AcceptedLimitation(
            LimitedAspect.PER_EVENT_PAGE,
            "each weekly party has one page that shows only its next night, so every night of a series points at that page",
            verified = "2026-10-17"
        )
    )
