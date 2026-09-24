package de.norm.events.scraper.aeg

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Shared importer for the two Berlin AEG venues, one Carbonhouse tenant and so one listing
 * shape: [EventSource.UBER_ARENA] and [EventSource.UBER_EATS_MUSIC_HALL]. Each reads its own
 * `/events/all` page — server-rendered, unpaginated — keeps everything not filed as sport, then
 * follows each row to its `/events/detail/<slug>/<YYYY-MM-DD-HHMM>` page for doors time,
 * description and ticket link.
 *
 * @see AegOverviewPageScraper for the listing (discovery, category, date, price).
 * @see AegDetailPageScraper for the detail pages (doors, description, ticket).
 */
@Suppress("AbstractClassCanBeConcreteClass") // A base for the venue importers below it; an instance of it alone names no venue.
abstract class AbstractAegVenueImporter(
    htmlFetcher: HtmlFetcher,
    override val eventSource: EventSource
) : AbstractTwoPageWebsiteImporter(htmlFetcher) {
    private val overviewPageScraper = AegOverviewPageScraper()
    private val detailPageScraper = AegDetailPageScraper()

    override fun scrapeOverview(
        document: Document,
        url: String
    ): List<ScrapedEvent> = overviewPageScraper.scrape(document, url, eventSource)

    override fun scrapeDetail(
        document: Document,
        url: String
    ): ScrapedEvent? = detailPageScraper.scrape(document, url, eventSource)

    /**
     * Merges detail-page data ([primary]) with listing data ([fallback]). The **listing wins on
     * title, status, date, start time, category, price and thumbnail** — the detail page states
     * none cleanly: its heading appends "in der <venue>", and it renders neither a date nor a
     * cancellation this parser reads. The detail page contributes only doors time, description and
     * ticket link. The artist roster also comes from the listing, where the clean title lives.
     *
     * **The roster is re-derived here, and only here, when the description can add to it.** The two
     * halves the rule needs never meet before this point: the title and its acts are the listing's,
     * the description is the detail page's. See [billedActs].
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.copy(
            title = fallback.title,
            status = fallback.status,
            eventDate = primary.eventDate.takeIf { it != UNRESOLVED_EVENT_DATE } ?: fallback.eventDate,
            startTime = primary.startTime ?: fallback.startTime,
            eventType = primary.eventType ?: fallback.eventType,
            imageUrl = primary.imageUrl ?: fallback.imageUrl,
            pricePresale = primary.pricePresale ?: fallback.pricePresale,
            priceNote = primary.priceNote ?: fallback.priceNote,
            artists = billedActs(primary, fallback)
        )

    /**
     * The merged roster: the listing's, unless it is a single act the detail page's description
     * shows to be a whole bill (#1832).
     *
     * `D-Block Europe, French Montana` is one artist row on the listing, because a single comma
     * decides nothing about a title; the description reads `D-Block Europe und French Montana …`
     * and settles it. The re-derivation is deliberately narrow: **only a one-act roster can grow**,
     * so a listing that already bills a co-bill correctly is never rebuilt, and the description can
     * only ever add acts the title already named.
     */
    private fun billedActs(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): List<ScrapedArtist> {
        val roster = primary.artists.ifEmpty { fallback.artists }
        val description = primary.description
        if (roster.size != 1 || description.isNullOrBlank()) return roster
        val corroborated =
            buildArtistsForEventType(
                fallback.title,
                subtitle = null,
                eventType = fallback.eventType,
                description = description
            )
        return corroborated.takeIf { it.size > roster.size } ?: roster
    }
}

/**
 * Uber Arena. Home to ALBA Berlin and the Eisbären, so a large share of its listing is sport,
 * deliberately not imported — its concert, show and comedy count is well below the row count.
 */
@Component
class UberArenaWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractAegVenueImporter(htmlFetcher, EventSource.UBER_ARENA)

/**
 * The Uber Eats Music Hall — the arena's smaller neighbour, whose listing omits the category
 * *name* the arena publishes and abbreviates its months in German.
 */
@Component
class UberEatsMusicHallWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractAegVenueImporter(htmlFetcher, EventSource.UBER_EATS_MUSIC_HALL)

/** Nothing this source withholds needs declaring (#715). */
val AEG_LIMITATIONS =
    VenueLimitations(
        sources =
            setOf(
                EventSource.UBER_ARENA,
                EventSource.UBER_EATS_MUSIC_HALL
            )
    )
