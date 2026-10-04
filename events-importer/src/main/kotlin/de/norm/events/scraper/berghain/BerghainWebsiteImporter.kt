package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.collapseExhibitionRuns
import de.norm.events.scraper.queryParameter
import de.norm.events.scraper.withQueryParameter
import de.norm.events.scraper.withSetTimesFrom
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Berghain's server-rendered programme.
 *
 * One importer serves both source rows on the identical template — the main `/de/program/`
 * page (Berghain building floors → parties) and `/de/program/kantine-am-berghain/` (concert
 * hall). Each row carries its own URL, ETag and venue; both dispatch via [EventSource.BERGHAIN].
 * A Kantine night takes the `kantine_am_berghain:` prefix and belongs to the Kantine row on
 * either page ([berghainSourceId], #2557).
 *
 * Inherited from [AbstractTwoPageWebsiteImporter]:
 * 1. Fetch the overview and discover events via [BerghainOverviewPageScraper] (authoritative
 * for title, date, times, floor and the lineup), following its later pages ([nextOverviewPage]).
 * 2. Each `/de/event/<id>/` page via [BerghainDetailPageScraper] for image, ticket link,
 * prices, description and the running order's set times.
 * 3. Merge: the detail page is primary, the overview fills gaps — crucially the artist lineup,
 * which only the overview parses cleanly, and to which the set times are attached.
 *
 * The site sends no `ETag` or `Last-Modified`, so every run reads every event page in full: a
 * running order published since the last run lands on the next one (#2002).
 *
 * @see BerghainOverviewPageScraper for overview parsing (discovery + lineup + fallback).
 * @see BerghainDetailPageScraper for detail parsing (image, prices, ticket, description).
 * @see <a href="https://www.berghain.berlin/de/program/">Berghain programme</a>
 */
@Component
class BerghainWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the overview scraper's past-event cutoff; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher, BerghainOverviewPageScraper(clock)::scrape, BerghainDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.BERGHAIN
    override val listsWholeProgramme: Boolean = true

    private val logger = KotlinLogging.logger {}

    /**
     * The main programme shows about three weeks, and its "Mehr" button loads `?page=N+1`. Page 2
     * still carries the button while page 3 comes back empty, so the walk also stops at a page
     * without events. Kantine renders its whole programme and has no button.
     */
    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? {
        if (document.selectFirst(LOAD_MORE_SELECTOR) == null || document.selectFirst(EVENT_LINK_SELECTOR) == null) return null
        val page = url.queryParameter(PAGE_PARAMETER)?.toIntOrNull() ?: 1
        return url.withQueryParameter(PAGE_PARAMETER, page + 1)
    }

    /**
     * Fills what the detail page could not supply from the overview event. The detail page is
     * authoritative for everything it parses; the overview contributes only where it returned null
     * — and the lineup, which is the overview's, with the detail page's set times attached.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // The overview's floors decided the owning source; a detail page without floors must not move the night.
            sourceId = fallback.sourceId,
            // The overview's lineup is authoritative; the detail page's artists carry only set times.
            // An exhibition's slot repeats its title, which is no act (#2265).
            artists =
                if (primary.eventType == EventType.EXHIBITION.name) {
                    emptyList()
                } else {
                    fallback.artists.withSetTimesFrom(primary.artists) {
                        logger.warn { "Running-order slot '${it.name}' matches no act on the programme; its set time is dropped" }
                    }
                }
        )

    /**
     * A Halle exhibition is listed once per open day, each day its own event page, so the days fold
     * into one run from the first listed day to the last (ADR-029, #2265), keyed on the show's title.
     * Only the page says EXHIBITION, so a day whose page failed takes the type from another day of the
     * same show ([withFailedExhibitionDaysTyped]). A failed day that no day answered for names its
     * run in [ScrapedEvent.storedRunId], and the upsert folds it in if that run is stored (#2575).
     */
    override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult =
        when (val result = super.importEvents(url, etag, lastModified)) {
            is ImportResult.Success -> {
                result.copy(
                    events =
                        result.events
                            .withFailedExhibitionDaysTyped()
                            .collapseExhibitionRuns(::exhibitionRunId)
                            .map { if (it.detailUnavailable && it.eventType != EventType.EXHIBITION.name) it.copy(storedRunId = exhibitionRunId(it)) else it }
                )
            }

            else -> {
                result
            }
        }

    /**
     * A day whose page failed carries the listing's type, a party billing the show's title as a DJ,
     * and would be inserted beside the run under its own `sourceId`. Where another day of the same
     * show answered EXHIBITION, the failed day takes that type and folds into the run (#2542).
     */
    private fun List<ScrapedEvent>.withFailedExhibitionDaysTyped(): List<ScrapedEvent> {
        val shows = filter { it.eventType == EventType.EXHIBITION.name }.map(::exhibitionRunId).toSet()
        return map { event ->
            if (event.detailUnavailable && exhibitionRunId(event) in shows) {
                event.copy(eventType = EventType.EXHIBITION.name, artists = emptyList())
            } else {
                event
            }
        }
    }

    /** The run's `sourceId`, from the show's title: every day of one show shares it. */
    private fun exhibitionRunId(event: ScrapedEvent): String = "${EventSource.BERGHAIN.sourceIdPrefix}exhibition-${SlugGenerator.slugify(event.title)}"

    private companion object {
        const val LOAD_MORE_SELECTOR = "button#load-more-events"
        const val EVENT_LINK_SELECTOR = "a[href^=/de/event/]"
        const val PAGE_PARAMETER = "page"
    }
}

val BERGHAIN_LIMITATIONS =
    VenueLimitations(
        EventSource.BERGHAIN,
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the Kantine and Halle pages name only the room and have no genre field, and the concerts there vary"
        )
    )
