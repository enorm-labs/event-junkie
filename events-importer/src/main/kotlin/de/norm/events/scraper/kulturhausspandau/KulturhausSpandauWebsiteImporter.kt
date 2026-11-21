package de.norm.events.scraper.kulturhausspandau

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
import java.time.YearMonth

/**
 * Website importer for Kulturhaus Spandau. The `/programm/` HTML holds only teasers, and the theme's script reads the
 * programme from `/wp-json/eventmanager/v1/calendar/`, one month per request, which answers without a key.
 *
 * The run reads the current month and the next [MONTHS_AHEAD], with no stop at an empty month: a thin spring can follow
 * two empty months. The window is a cap, so the import does not claim to hold the whole programme. One failed month fails
 * the run, because a partial read would stale the missing month's events.
 */
@Component
class KulturhausSpandauWebsiteImporter(
    private val apiClient: ApiClient,
    private val clock: Clock = Clock.system(BERLIN)
) : EventImporter {
    private val logger = KotlinLogging.logger {}

    override val eventSource: EventSource = EventSource.KULTURHAUS_SPANDAU

    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val first = YearMonth.now(clock)
        val months = (0L..MONTHS_AHEAD).map { first.plusMonths(it) }.map { apiClient.fetchJson("$url?month=${it.monthValue}&year=${it.year}") }
        val events = KulturhausSpandauApiScraper().scrape(months, url)
        logger.info { "Scraped ${events.size} event(s) from Kulturhaus Spandau" }
        return ImportResult.Success(events = events, etag = null, lastModified = null)
    }

    private companion object {
        /** The programme reached seven months ahead when the route was read; a year leaves room. */
        const val MONTHS_AHEAD = 11L
    }
}

val KULTURHAUS_SPANDAU_LIMITATIONS =
    VenueLimitations(
        EventSource.KULTURHAUS_SPANDAU,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the API carries no event text, and the event page is a teaser", verified = "2026-10-23"),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name a format, not a music style", verified = "2026-10-23"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the API gives one start time per event", verified = "2026-10-23"),
        AcceptedLimitation(
            LimitedAspect.PRICE_BOX_OFFICE,
            "the API gives one admission price and the concessions, no box-office price",
            verified = "2026-10-23"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the `organizer_id` is empty on every event", verified = "2026-10-23")
    )
