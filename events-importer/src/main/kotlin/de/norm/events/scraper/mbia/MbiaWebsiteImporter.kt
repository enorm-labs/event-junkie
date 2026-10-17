package de.norm.events.scraper.mbia

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import de.norm.events.scraper.walkWpRestPages
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for M-BIA, a WordPress site whose only content type is one post per night
 * (`/wp-json/wp/v2/posts`). The venue sets each post's date to the night's date, and the homepage
 * renders it as such; the flyers agree.
 *
 * `_embed=wp:featuredmedia` brings the flyer in the same response: the site keeps about ten posts, so
 * the embed costs a few kilobytes where a second request would cost a round trip. Newest-first by
 * post date is newest-first by night, so the walk ends at the first page that reaches the past.
 *
 * The body of every post is one Resident Advisor link. It is stored as the ticket URL and never fetched.
 */
@Component
class MbiaWebsiteImporter(
    private val apiClient: ApiClient,
    private val clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.M_BIA

    private val apiScraper = MbiaApiScraper(clock)

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val today = LocalDate.now(clock)
        val listing =
            apiClient.walkWpRestPages(eventSource, { page -> pageUrl(url, page) }, PER_PAGE, MAX_PAGES, apiScraper::scrape) { page ->
                page.oldestDate?.let { it < today } == true
            }
        val events = listing.items.distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} upcoming event(s) from M-BIA" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = listing.complete)
    }

    /** One listing page on the configured `/wp-json/wp/v2/posts` base, with the fields the parser reads. */
    private fun pageUrl(
        baseUrl: String,
        page: Int
    ): String = "$baseUrl${baseUrl.querySeparator()}per_page=$PER_PAGE&page=$page&_fields=$FIELDS&_embed=wp:featuredmedia"

    private companion object {
        const val PER_PAGE = 100

        /** A bound on the walk; one page holds the whole programme. */
        const val MAX_PAGES = 5

        /** `_links` and `_embedded` must be listed, or `_fields` drops the embedded flyer. */
        const val FIELDS = "id,date,link,title,content,_links.wp:featuredmedia,_embedded"
    }
}

val M_BIA_LIMITATIONS =
    VenueLimitations(
        EventSource.M_BIA,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "a post carries only the night's name"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the body of every post is a single ticket link"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the site names no category; every night is a club night"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site shows only the date of a night"),
        AcceptedLimitation(LimitedAspect.START_TIME, "the site shows only the date; every post carries the same 23:00 scheduling time"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints no end time"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no style per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price; tickets link to Resident Advisor"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a post names acts only in its title, and only some titles name any"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits no promoter beside the night's name"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled night can only disappear")
    )
