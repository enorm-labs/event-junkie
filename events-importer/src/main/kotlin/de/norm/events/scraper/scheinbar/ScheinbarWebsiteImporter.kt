package de.norm.events.scraper.scheinbar

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FetchResult
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.readEventPage
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for the Scheinbar Varieté in Schöneberg. `/programm/` lists about three months
 * on one page; each programme page is then read once, for its text and photo, and applied to every
 * evening that links it. A host's four Open Stage nights share one page, which is why this does not
 * use [de.norm.events.scraper.AbstractSinglePageWebsiteImporter.enrichFromEventPage], which reads a
 * page per evening. An evening whose page fails is flagged, so the upsert keeps what it stored.
 *
 * The stage plays one show a night, so the date is the evening's identity.
 *
 * @see ScheinbarProgrammPageScraper for the listing.
 * @see <a href="https://www.scheinbar.de/programm/">Scheinbar Varieté Programm</a>
 */
@Component
class ScheinbarWebsiteImporter(
    private val htmlFetcher: HtmlFetcher
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SCHEINBAR
    override val fetchesBeyondEntryPage: Boolean = true

    private val listingScraper = ScheinbarProgrammPageScraper()
    private val detailScraper = ScheinbarDetailPageScraper()

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
                val events = listingScraper.scrape(fetchResult.document, url)
                logger.info { "Scraped ${events.size} event(s) from Scheinbar Varieté" }
                ImportResult.Success(
                    events = withProgrammePages(events),
                    etag = fetchResult.etag,
                    lastModified = fetchResult.lastModified
                )
            }
        }

    private suspend fun withProgrammePages(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val programmes = events.distinctBy { it.sourceUrl }.associate { it.sourceUrl to htmlFetcher.readEventPage(it, detailScraper::scrape) }
        return events.map { event ->
            programmes[event.sourceUrl]?.let { event.copy(description = it.description, imageUrl = it.imageUrl) }
                ?: event.copy(detailUnavailable = true)
        }
    }
}

val SCHEINBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.SCHEINBAR,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme states one time per evening"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "tickets are reserved online and paid at the box office, at one price"),
        AcceptedLimitation(LimitedAspect.GENRE, "the house names no genre; an Open Stage night mixes comedy, magic, music and artistry"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house presents every evening itself")
    )
