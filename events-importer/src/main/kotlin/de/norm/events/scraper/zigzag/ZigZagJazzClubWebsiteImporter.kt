package de.norm.events.scraper.zigzag

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Zig Zag Jazz Club, a Squarespace site.
 *
 * `/programmneu` lists the programme ([ZigZagJazzClubOverviewPageScraper]); each event page adds
 * the start and doors times, the admission, the ticket link and the blurb
 * ([ZigZagJazzClubDetailPageScraper]). A failed page costs only those fields. A night whose page
 * names another location is dropped, because that concert is the other house's event. The
 * collection's `?format=json` and month views are disallowed by `robots.txt`, so the HTML is the
 * source.
 *
 * @see <a href="https://www.zigzag-jazzclub.berlin/programmneu">Zig Zag programme</a>
 */
@Component
class ZigZagJazzClubWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ZIG_ZAG_JAZZ_CLUB
    override val fetchesBeyondEntryPage: Boolean = true

    private val overviewPageScraper = ZigZagJazzClubOverviewPageScraper(clock)
    private val detailPageScraper = ZigZagJazzClubDetailPageScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val fetchResult = htmlFetcher.fetch(url, etag, lastModified)) {
            is FetchResult.NotModified -> {
                ImportResult.NotModified
            }

            is FetchResult.Success -> {
                val events = overviewPageScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from the Zig Zag programme" }

                ImportResult.Success(
                    events = events.mapNotNull { withDetail(it) },
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    @Suppress("TooGenericExceptionCaught") // Intentional: a broken event page must not fail the whole import
    private suspend fun withDetail(event: ScrapedEvent): ScrapedEvent? {
        val detail =
            try {
                detailPageScraper.scrape(htmlFetcher.fetchDocument(event.sourceUrl))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to fetch event page for '${event.title}' (${event.sourceUrl}), keeping listing data" }
                return event
            }
        if (detail.elsewhere) logger.info { "Dropping '${event.title}': its page names another location" }
        return detail.takeUnless { it.elsewhere }?.let {
            event.copy(
                description = it.description,
                startTime = it.startTime,
                doorsTime = it.doorsTime,
                pricePresale = it.price?.takeIf { _ -> it.ticketUrl != null },
                priceBoxOffice = it.price,
                ticketUrl = it.ticketUrl
            )
        }
    }
}

val ZIG_ZAG_JAZZ_CLUB_LIMITATIONS =
    VenueLimitations(
        EventSource.ZIG_ZAG_JAZZ_CLUB,
        AcceptedLimitation(LimitedAspect.END_TIME, "the end time is a calendar default, 23:59 on most nights"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the club presents every night itself"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night")
    )
