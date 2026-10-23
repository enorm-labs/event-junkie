package de.norm.events.scraper.interkosmos

import de.norm.events.common.BERLIN
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Interkosmos. The configured URL is the theme's `wp/v2/events` route. One request asks for the
 * one-off events of the next year, which the home page's script asks for by `dateRangeStart` and `dateRangeEnd`, and one
 * asks for the rules with `repetition=true`. No conditional request, as the REST API sends no validators.
 *
 * @see InterkosmosApiScraper for the fields and the rules.
 */
@Component
class InterkosmosWebsiteImporter(
    private val apiClient: ApiClient,
    private val clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.INTERKOSMOS

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val today = LocalDate.now(clock)
        val oneOffs = apiClient.fetchJson("$url?dateRangeStart=$today&dateRangeEnd=${today.plusYears(1)}&orderby=_ik_date&order=asc&per_page=$PAGE_SIZE")
        val rules = apiClient.fetchJson("$url?repetition=true&per_page=$PAGE_SIZE")
        val events = InterkosmosApiScraper().scrape(oneOffs, rules, today)
        logger.info { "Scraped ${events.size} event(s) from Interkosmos" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        /** The REST API's maximum; the house lists about a dozen. */
        const val PAGE_SIZE = 100
    }
}

val INTERKOSMOS_LIMITATIONS =
    VenueLimitations(
        EventSource.INTERKOSMOS,
        AcceptedLimitation(LimitedAspect.GENRE, "the event types name a format, not a music style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "an event has a start and an end time, no doors"),
        AcceptedLimitation(LimitedAspect.PRICE, "`ik_price` is empty on most one-off events"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "`ik_price` is one number, and most events leave it empty"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the API carries no sold-out flag"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "a recurring night is a rule with no exceptions, so a cancelled night still shows"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house hosts its own nights, and an outside host is named only in the text")
    )
