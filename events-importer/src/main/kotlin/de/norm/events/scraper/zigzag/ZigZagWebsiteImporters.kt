package de.norm.events.scraper.zigzag

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventPageReader
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Shared importer for the two Zig Zag venues, a Squarespace site.
 *
 * `/programmneu` lists both venues' programmes ([ZigZagOverviewPageScraper] keeps one); each event
 * page adds the start and doors times, the admission, the ticket link and the blurb
 * ([ZigZagDetailPageScraper]). Its line-up bills a night whose title names no act, such as a
 * tribute or the jam session; a title's act stays the only one billed. A failed or empty page
 * keeps the listing row, flagged so the upsert keeps the stored fields (see #2471). The
 * collection's `?format=json` and month views are disallowed by `robots.txt`, so the HTML is the
 * source.
 *
 * @see <a href="https://www.zigzag-jazzclub.berlin/programmneu">Zig Zag programme</a>
 */
abstract class AbstractZigZagWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    final override val eventSource: EventSource,
    clock: Clock
) : AbstractSinglePageWebsiteImporter(htmlFetcher, eventSource.name, ZigZagOverviewPageScraper(eventSource, clock)::scrape) {
    // This base's own logger, not the pipeline's: the lines keep the name they always had.
    private val logger = KotlinLogging.logger {}

    private val detailPageScraper = ZigZagDetailPageScraper()

    /** Whether a night whose page names a location other than the club's is still this venue's. */
    protected abstract val keepsElsewhere: Boolean

    override val listedLine: ((Int) -> String)? = null

    override suspend fun afterListing(
        events: List<ScrapedEvent>,
        document: Document,
        url: String
    ): List<ScrapedEvent> {
        logger.info { "Scraped ${events.size} event(s) for $eventSource from the Zig Zag programme" }
        return events
    }

    override val eventPageReader =
        EventPageReader({ _, document -> detailPageScraper.scrape(document).takeUnless(ZigZagDetail::isEmpty) }, ::withDetail)

    /** The event with its page's fields, or null when the page moves it elsewhere. */
    private fun withDetail(
        event: ScrapedEvent,
        detail: ZigZagDetail
    ): ScrapedEvent? {
        if (detail.elsewhere && !keepsElsewhere) {
            logger.info { "Dropping '${event.title}': its page names another location" }
            return null
        }
        return event.copy(
            description = detail.description,
            descriptionAlt = detail.descriptionAlt,
            startTime = detail.startTime,
            doorsTime = detail.doorsTime,
            pricePresale = detail.price?.takeIf { detail.ticketUrl != null },
            priceBoxOffice = detail.price,
            ticketUrl = detail.ticketUrl,
            artists = event.artists.ifEmpty { detail.artists }
        )
    }
}

/**
 * Zig Zag Jazz Club — the items without the hall's prefix. A night whose page names another
 * location is dropped, because that concert is the other house's event.
 */
@Component
class ZigZagJazzClubWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
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
    clock: Clock = Clock.system(BERLIN)
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

/** A page that rendered without its content block, the shape a redirect to the programme leaves. */
private fun ZigZagDetail.isEmpty(): Boolean = !elsewhere && artists.isEmpty() && listOf(description, startTime, doorsTime, price, ticketUrl).all { it == null }
