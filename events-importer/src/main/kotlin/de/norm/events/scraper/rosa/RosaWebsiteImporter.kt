package de.norm.events.scraper.rosa

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * The cookie ROSA's age gate sets on a visitor who confirms they are 18 or over.
 *
 * Without it `/dates` serves the gate and no programme at all. The value is a self-certification
 * flag rather than a credential: nothing is authenticated, and the same page is public to anyone
 * who clicks through. It is sent with the fetch because the alternative is importing an empty
 * programme from a venue that publishes one.
 */
private val AGE_GATE_COOKIE = mapOf("rosa_age_ok" to "1")

/**
 * Website importer for ROSA Berlin's Next.js programme page.
 *
 * One fetch per import: the listing carries every event, and the parsing lives in
 * [RosaOverviewPageScraper]. The fetch sends [AGE_GATE_COOKIE], which is the only reason this
 * importer needs anything the other single-page venues do not.
 *
 * @see <a href="https://www.rosaclub.de/dates">ROSA dates</a>
 */
@Component
class RosaWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ROSA

    private val overviewPageScraper = RosaOverviewPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified, AGE_GATE_COOKIE)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from ROSA" }

                ImportResult.Success(
                    events = events,
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }
}

val ROSA_LIMITATIONS =
    VenueLimitations(
        EventSource.ROSA,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the site states one title per night and no second line"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes an opening range, not a doors time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the venue names no musical style anywhere"),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue sells through Resident Advisor and prints no price"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the site names the party series, never who plays it"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the party name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site links to the ticket shop rather than stating a status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site drops a cancelled night instead of marking it"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one page with an anchor per night")
    )
