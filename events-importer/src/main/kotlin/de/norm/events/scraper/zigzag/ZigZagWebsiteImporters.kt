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
import io.github.oshai.kotlinlogging.Level
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Shared importer for the two Zig Zag venues, a Squarespace site.
 *
 * `/programmneu` lists both venues' programmes; [ZigZagOverviewPageScraper] keeps one, and for the
 * hall also the items that only their page can place. Each event page adds the start and doors times, the admission, the ticket link and the blurb
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

    /** Whether [event]'s page, which parsed to [detail], says that the night is this venue's. */
    protected abstract fun isHere(
        event: ScrapedEvent,
        detail: ZigZagDetail
    ): Boolean

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

    /** The event with its page's fields, or null when the page gives it to another house. */
    private fun withDetail(
        event: ScrapedEvent,
        detail: ZigZagDetail
    ): ScrapedEvent? {
        if (!isHere(event, detail)) {
            // Every club night reaches the hall's run as a candidate, so its drop is not worth an INFO line.
            logger.at(if (event.venueUnconfirmed) Level.DEBUG else Level.INFO) { message = "Dropping '${event.title}': its page names another location" }
            return null
        }
        return event.copy(
            venueUnconfirmed = false,
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
    override fun isHere(
        event: ScrapedEvent,
        detail: ZigZagDetail
    ): Boolean = !detail.elsewhere
}

/**
 * Zig Zag Hall — the items titled `ZIG ZAG HALL: …`, and the items without that prefix whose page
 * names the hall's address (#5474). A prefixed night needs no address, because its page can name
 * the location only in an image. A night whose page names another place or none is not the hall's,
 * and one whose page failed is left out with its stored event spared.
 */
@Component
class ZigZagHallWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : AbstractZigZagWebsiteImporter(htmlFetcher, EventSource.ZIG_ZAG_HALL, clock) {
    override val doneLine: (kept: Int, listed: Int) -> String = { kept, listed -> "Kept $kept of $listed listed night(s) for $eventSource" }

    override fun isHere(
        event: ScrapedEvent,
        detail: ZigZagDetail
    ): Boolean = !event.venueUnconfirmed || detail.atHall
}

private val ZIG_ZAG_SHARED_LIMITATIONS =
    listOf(
        AcceptedLimitation(LimitedAspect.END_TIME, "the end time is a calendar default, 23:59 on most nights", verified = "2026-10-01"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the club presents every night itself, in both houses", verified = "2026-10-01"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status", verified = "2026-10-01")
    )

val ZIG_ZAG_HALL_LIMITATIONS = VenueLimitations(setOf(EventSource.ZIG_ZAG_HALL), ZIG_ZAG_SHARED_LIMITATIONS)

/** The presale text is "Für dieses Konzert steht kein Online-Vorverkauf zur Verfügung"; the reservation is paid at the door. */
val ZIG_ZAG_JAZZ_CLUB_LIMITATIONS =
    VenueLimitations(
        setOf(EventSource.ZIG_ZAG_JAZZ_CLUB),
        ZIG_ZAG_SHARED_LIMITATIONS +
            AcceptedLimitation(
                LimitedAspect.TICKET_URL,
                "a night without online presale takes reservations paid at the door, and links no shop",
                verified = "2026-11-24"
            )
    )

/** A page that rendered without its content block, the shape a redirect to the programme leaves. */
private fun ZigZagDetail.isEmpty(): Boolean = !elsewhere && artists.isEmpty() && listOf(description, startTime, doorsTime, price, ticketUrl).all { it == null }
