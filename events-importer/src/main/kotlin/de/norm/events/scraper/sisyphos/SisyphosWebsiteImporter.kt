package de.norm.events.scraper.sisyphos

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Sisyphos, read from its Shopify ticket shop.
 *
 * The club publishes no programme. Its only site is the merch shop, whose `TICKETS` collection
 * carries the few nights sold in advance — one `generationS` a month beside the T-shirts — while
 * the every-weekend programme stays off the web. The import is those ticketed specials, not the
 * club's calendar, and a count of one or two is the source, not a broken parser.
 *
 * Shopify exposes every collection as JSON at `/collections/<handle>/products.json`, so one
 * [ApiClient] request feeds [SisyphosApiScraper]. The endpoint answers with a weak `ETag` that
 * [ApiClient] does not read, so every import returns [ImportResult.Success] and the idempotent
 * `sourceId` upsert absorbs the repeat.
 */
@Component
class SisyphosWebsiteImporter(
    private val apiClient: ApiClient
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.SISYPHOS

    private val apiScraper = SisyphosApiScraper()

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val events = apiScraper.scrape(apiClient.fetchJson(url), url)
        logger.info { "Scraped ${events.size} event(s) from Sisyphos" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }
}

val SISYPHOS_LIMITATIONS =
    VenueLimitations(
        EventSource.SISYPHOS,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the shop files every night as a ticket product with no category; each is stored as a party"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "a ticket product names a day and never a time"),
        AcceptedLimitation(LimitedAspect.START_TIME, "a ticket product names a day and never a time"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the shop names no DJ anywhere; a night is sold under its series name"),
        AcceptedLimitation(LimitedAspect.GENRE, "the shop names no musical style"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the shop sells online only and states no door price"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a cancelled night is removed from the shop rather than marked")
    )
