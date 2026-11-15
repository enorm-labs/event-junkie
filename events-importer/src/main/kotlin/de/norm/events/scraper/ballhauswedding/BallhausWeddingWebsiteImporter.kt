package de.norm.events.scraper.ballhauswedding

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.readEventPage
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.withEventPages
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Ballhaus Wedding: the `/veranstaltungen` programme, then the Wix Events page of each entry that
 * links one. About a third do; the others link a promoter's shop or nothing.
 *
 * An entry without a link can still have a dated page in `event-pages-sitemap.xml` ([BallhausWeddingSitemap]). Its
 * copies are read newest first, and the first one not cancelled enriches the entry as a linked page does. A cancelled
 * copy is skipped, because the venue cancels a page it replaces: the programme says whether the night is off. The entry
 * keeps a ticket link of its own and takes the page's otherwise. An entry with no such page keeps the programme's text.
 *
 * It implements [EventImporter] directly. The programme carries no validators that cover the event pages, so every run
 * reads it in full and stores none. `AbstractSinglePageWebsiteImporter` fetches the programme conditionally and stores
 * its validators, and its end line cannot count the entries with an event page.
 *
 * @see BallhausWeddingOverviewPageScraper for the programme.
 * @see BallhausWeddingEventPageScraper for the event pages.
 */
@Component
class BallhausWeddingWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    /** Dates the year-less programme and drops the past. Defaults to the system clock; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}
    private val overviewPageScraper = BallhausWeddingOverviewPageScraper()
    private val eventPageScraper = BallhausWeddingEventPageScraper()

    override val eventSource: EventSource = EventSource.BALLHAUS_WEDDING
    override val listsWholeProgramme: Boolean = true
    override val fetchesBeyondEntryPage: Boolean = true

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val listing = overviewPageScraper.scrape(htmlFetcher.fetchDocument(url), url, LocalDate.now(clock))
        val linked =
            withEventPages(htmlFetcher::fetchDocument, listing, EVENT_PAGE_OWNS, hasEventPage = { it.sourceUrl != url }) { event, document ->
                eventPageScraper.enrich(event, document)
            }
        val events = withSitemapPages(linked, url)
        logger.info { "Scraped ${events.size} Ballhaus Wedding event(s), ${events.count { it.sourceUrl != url }} with an event page" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    /** [events] with each unlinked entry enriched from its dated sitemap page, or unchanged when the sitemap fails. */
    private suspend fun withSitemapPages(
        events: List<ScrapedEvent>,
        programmeUrl: String
    ): List<ScrapedEvent> {
        val sitemapUrl = resolveUrl(programmeUrl, SITEMAP_PATH)
        val pages = readSitemap(sitemapUrl) ?: return events
        val matches = BallhausWeddingSitemap.match(events, pages, programmeUrl)
        logger.at(Level.INFO) {
            message = "The sitemap lists ${pages.size} dated Wix Events page(s); ${matches.size} unlinked programme entr(ies) match one"
            payload = mapOf(LogFields.URL to sitemapUrl)
        }
        return events.map { event -> matches[event.sourceId]?.let { fromSitemapPage(event, it) } ?: event }
    }

    @Suppress("TooGenericExceptionCaught") // Intentional: without the sitemap the programme still imports
    private suspend fun readSitemap(sitemapUrl: String): List<DatedEventPage>? =
        try {
            BallhausWeddingSitemap.datedPages(htmlFetcher.fetchDocument(sitemapUrl))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.at(Level.WARN) {
                message = "Failed to read the Ballhaus Wedding sitemap, importing the unlinked entries without a Wix Events page"
                cause = e
                payload = mapOf(LogFields.URL to sitemapUrl)
            }
            null
        }

    /**
     * [event] enriched from the first of [pageUrls] that is not cancelled. When none is and one failed to load, the row is
     * flagged as a linked page's would be, so the stored page fields stay.
     */
    private suspend fun fromSitemapPage(
        event: ScrapedEvent,
        pageUrls: List<String>
    ): ScrapedEvent {
        var failed = false
        for (pageUrl in pageUrls) {
            val page = event.copy(sourceUrl = pageUrl, ticketUrl = event.ticketUrl ?: pageUrl)
            val read =
                readEventPage(htmlFetcher::fetchDocument, page) { document ->
                    SitemapPage(page.takeUnless { eventPageScraper.isCancelled(document) }?.let { eventPageScraper.enrich(it, document) })
                }
            read?.enriched?.let { return it }
            failed = failed || read == null
        }
        return if (failed) event.copy(detailUnavailable = true, detailPageOwns = EVENT_PAGE_OWNS) else event
    }

    /** One sitemap page as read: the enriched entry, or null when the page is cancelled or carries no `Event`. */
    private class SitemapPage(
        val enriched: ScrapedEvent?
    )

    private companion object {
        const val SITEMAP_PATH = "/event-pages-sitemap.xml"
        val EVENT_PAGE_OWNS = setOf(ScrapedField.DESCRIPTION, ScrapedField.IMAGE, ScrapedField.START_TIME)
    }
}

val BALLHAUS_WEDDING_LIMITATIONS =
    VenueLimitations(
        EventSource.BALLHAUS_WEDDING,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no category, so the type comes from words in the title, and a night without one is typed other",
            verified = "2026-10-08"
        ),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the programme has no line-up field; only a bare `<name> - Konzert` entry bills its act",
            verified = "2026-10-08"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "only the entries with a Wix Events page have a poster; the programme has none", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.GENRE, "nothing on the programme names a style", verified = "2026-10-08"),
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the programme states one house rule, doors 45 minutes before a seated show, and no doors time per event",
            verified = "2026-10-08"
        ),
        AcceptedLimitation(LimitedAspect.END_TIME, "only the Wix Events pages state an end", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "an entry without a Wix Events page exists only on the programme page", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PRICE, "a concert prints a price line; a dance social or a hired night prints none", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13")
    )
