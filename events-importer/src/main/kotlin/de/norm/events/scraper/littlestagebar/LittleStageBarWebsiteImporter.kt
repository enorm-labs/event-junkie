package de.norm.events.scraper.littlestagebar

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for the Little Stage Bar, a WordPress.com blog. It reads the site's RSS feed
 * (`/feed/`, the ten newest posts with their full text) and [LittleStageBarFeedScraper] parses it.
 *
 * The feed is XML, so it is fetched as text and not through the HTML parser, without validators.
 * The WordPress.com REST API is not used: `public-api.wordpress.com/robots.txt` disallows every
 * path, and the site's own `/wp-json/` answers 404.
 *
 * @see <a href="https://littlestageclubneukoelln.wordpress.com/">Little Stage Bar</a>
 */
@Component
class LittleStageBarWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.LITTLE_STAGE_BAR

    private val scraper = LittleStageBarFeedScraper(clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = scraper.scrape(htmlFetcher.fetchHtml(url), url)
        logger.info { "Scraped ${events.size} event(s) from the Little Stage Bar feed" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

val LITTLE_STAGE_BAR_LIMITATIONS =
    VenueLimitations(
        EventSource.LITTLE_STAGE_BAR,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "a post has a title and prose, no second title line"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the posts rarely state an end time and never in one form"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets and links no ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the posts name styles only in prose and hashtags"),
        AcceptedLimitation(LimitedAspect.PRICE, "the posts state no price beside free entry and donations"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the line-up is prose, often first names with instruments"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the posts credit no promoter"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the bar states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the posts have no status field"),
        AcceptedLimitation(LimitedAspect.PAGINATION, "the feed carries the ten newest posts, and older posts are past events")
    )
