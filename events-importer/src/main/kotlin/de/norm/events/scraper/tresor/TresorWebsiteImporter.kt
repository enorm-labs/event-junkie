package de.norm.events.scraper.tresor

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.withSetTimesFrom
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component

/**
 * Website importer for Tresor's club programme.
 *
 * WordPress with the REST API disabled site-wide (`401 rest_disabled`) and no structured data,
 * so two HTML pages:
 * 1. [HtmlFetcher] fetches `/club/events/` conditionally.
 * 2. [TresorOverviewPageScraper] parses the items — discovery list, date, title and the
 * floor-grouped lineup.
 * 3. Each `/event/YYYYMMDD-<slug>/` page via [TresorDetailPageScraper] — the start time (the
 * night's opening set) and the blurb.
 *
 * @see TresorOverviewPageScraper for listing parsing.
 * @see TresorDetailPageScraper for the set times and blurb.
 * @see <a href="https://tresorberlin.com/club/events/">Tresor Berlin</a>
 */
@Component
class TresorWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, TresorOverviewPageScraper()::scrape, TresorDetailPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.TRESOR
    override val listsWholeProgramme: Boolean = true

    private val logger = KotlinLogging.logger {}

    /**
     * Merges event-page data ([primary]) with listing data ([fallback]). The event page is the only
     * source of start time, blurb and set times. The **title** keeps the listing's value, because
     * the event page renders no heading and its document title carries the site name; the lineup
     * prefers the listing's too, as the one the venue curates as the programme, and takes the event
     * page's set times. An act the event page bills live is live: its `.lineup-subtitle` can be the
     * only mark, as Sue Léwig's is (#5400).
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            title = fallback.title,
            artists =
                fallback.artists
                    .ifEmpty { primary.artists }
                    .withSetTimesFrom(primary.artists) {
                        logger.warn { "Running-order slot '${it.name}' matches no act on the listing; its set time is dropped" }
                    }.withLiveRolesFrom(primary.artists)
        )

    private fun List<ScrapedArtist>.withLiveRolesFrom(runningOrder: List<ScrapedArtist>): List<ScrapedArtist> {
        val live = runningOrder.filter { it.role == ArtistRole.LIVE }.mapTo(mutableSetOf()) { SlugGenerator.slugify(it.name) }
        return map { if (SlugGenerator.slugify(it.name) in live) it.copy(role = ArtistRole.LIVE) else it }
    }
}

val TRESOR_LIMITATIONS =
    VenueLimitations(
        EventSource.TRESOR,
        AcceptedLimitation(
            LimitedAspect.DOORS_TIME,
            "the venue states no doors or start time; the night's opening set is the only clock it gives, and that is stored as the start",
            verified = "2026-08-25"
        ),
        AcceptedLimitation(LimitedAspect.START_TIME, "a night without set times names no clock at all", verified = "2026-10-17"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club states no category; every listing is a club night", verified = "2026-09-20"),
        AcceptedLimitation(LimitedAspect.PRICE, "the club sells at the door and prints no figure on its programme", verified = "2026-09-30"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "a night sold only at the door links no shop", verified = "2026-11-24"),
        AcceptedLimitation(
            LimitedAspect.PROMOTERS,
            "a collective is named only in the title, and read only where the title marks its host: hosted by, presents, invites, meets",
            verified = "2026-11-15",
            issue = 5080
        ),
        houseGenre = "Techno"
    )
