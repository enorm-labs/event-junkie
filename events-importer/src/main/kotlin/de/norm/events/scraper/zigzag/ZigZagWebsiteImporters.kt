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
 * Shared importer for the two Zig Zag venues, a Squarespace site.
 *
 * `/programmneu` lists both venues' programmes ([ZigZagOverviewPageScraper] keeps one); each event
 * page adds the start and doors times, the admission, the ticket link and the blurb
 * ([ZigZagDetailPageScraper]). A failed page costs only those fields. The collection's
 * `?format=json` and month views are disallowed by `robots.txt`, so the HTML is the source.
 *
 * @see <a href="https://www.zigzag-jazzclub.berlin/programmneu">Zig Zag programme</a>
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
abstract class AbstractZigZagWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    final override val eventSource: EventSource,
    clock: Clock
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val fetchesBeyondEntryPage: Boolean = true

    private val overviewPageScraper = ZigZagOverviewPageScraper(eventSource, clock)
    private val detailPageScraper = ZigZagDetailPageScraper()

    /** Whether a night whose page names a location other than the club's is still this venue's. */
    protected abstract val keepsElsewhere: Boolean

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
                logger.info { "Scraped ${events.size} event(s) for $eventSource from the Zig Zag programme" }

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
        val dropped = detail.elsewhere && !keepsElsewhere
        if (dropped) logger.info { "Dropping '${event.title}': its page names another location" }
        return detail.takeUnless { dropped }?.let {
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

/**
 * Zig Zag Jazz Club — the items without the hall's prefix. A night whose page names another
 * location is dropped, because that concert is the other house's event.
 */
@Component
class ZigZagJazzClubWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractZigZagWebsiteImporter(htmlFetcher, EventSource.ZIG_ZAG_JAZZ_CLUB, clock) {
    override val keepsElsewhere: Boolean = false
}

/**
 * Zig Zag Hall — the items titled `ZIG ZAG HALL: …`. Every hall page points away from the club,
 * since the hall is the other location, so none is dropped for it.
 */
@Component
class ZigZagHallWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.systemDefaultZone()
) : AbstractZigZagWebsiteImporter(htmlFetcher, EventSource.ZIG_ZAG_HALL, clock) {
    override val keepsElsewhere: Boolean = true
}

val ZIG_ZAG_LIMITATIONS =
    VenueLimitations(
        sources = setOf(EventSource.ZIG_ZAG_HALL, EventSource.ZIG_ZAG_JAZZ_CLUB),
        limitations =
            listOf(
                AcceptedLimitation(LimitedAspect.END_TIME, "the end time is a calendar default, 23:59 on most nights"),
                AcceptedLimitation(LimitedAspect.PROMOTERS, "the club presents every night itself, in both houses"),
                AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
                AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no cancelled marker for a night")
            )
    )
