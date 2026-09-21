package de.norm.events.scraper.zenner

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.gatsbyPageDataUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Zenner Berlin, a Gatsby front end over a Sanity CMS: the artefact URL is
 * derived from the configured programme page ([gatsbyPageDataUrl]), fetched via
 * [ApiClient.fetchJson], and parsed via [ZennerApiScraper] with the programme page as every
 * event's `sourceUrl`. The programme page is what the source stores, the venue's real entry
 * point; `/page-data/<path>/page-data.json` is a fixed Gatsby convention (ADR-007). No
 * conditional request: the artefact is regenerated on every site rebuild, so its validators
 * track the build, not the programme.
 *
 * @see ZennerApiScraper for the JSON parsing logic.
 * @see <a href="https://zenner.berlin/programm">Zenner Programm</a>
 */
@Component
class ZennerWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.ZENNER

    private val apiScraper = ZennerApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val json = apiClient.fetchJson(gatsbyPageDataUrl(url))
        val events = apiScraper.scrape(json, url)
        logger.info { "Scraped ${events.size} event(s) from Zenner" }

        // The artefact's ETag / Last-Modified track the build; change detection relies on idempotent
        // upserts.
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

val ZENNER_LIMITATIONS =
    VenueLimitations(
        EventSource.ZENNER,
        AcceptedLimitation(LimitedAspect.PRICE, "the venue publishes no prices"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors times"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue publishes no sold-out state"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue publishes no per-event pages")
    )
