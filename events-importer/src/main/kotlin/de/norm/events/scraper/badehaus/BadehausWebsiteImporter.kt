package de.norm.events.scraper.badehaus

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.buildArtistsForEventType
import org.springframework.stereotype.Component

/**
 * Website importer for Badehaus Berlin's WordPress `/events/` programme.
 *
 * Overview → detail:
 * 1. [HtmlFetcher] fetches `/events/` (ETag / Last-Modified); [BadehausOverviewPageScraper]
 * discovers every event — authoritative for the sold-out flag (a CSS class on the card), the
 * subtitle and the inferred event type, plus fallback title/date/doors/image.
 * 2. Each `/events/<slug>/` detail page via [BadehausDetailPageScraper] — primary for the full
 * description, the start time (`Beginn`) and the promoter, which the card omits.
 *
 * The configured source URL must point at `/events/` (the homepage is a separate landing page).
 *
 * @see BadehausOverviewPageScraper for discovery + the authoritative fields.
 * @see BadehausDetailPageScraper for the enriched per-event fields.
 * @see <a href="https://badehaus-berlin.com/events/">Badehaus Berlin programme</a>
 */
@Component
class BadehausWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, BadehausOverviewPageScraper()::scrape, BadehausDetailPageScraper()::scrape) {
    /** The listing's type is a guess from the title; the event page names the venue's category (#2505). */
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.EVENT_TYPE, ScrapedField.ARTISTS)

    override val eventSource: EventSource = EventSource.BADEHAUS
    override val listsWholeProgramme: Boolean = true

    /**
     * Merges detail-page data ([primary]) with overview data ([fallback]). The detail page is
     * authoritative for what only it carries — description, start time, promoter, the category
     * and the status its notice announces. The overview is authoritative for the sold-out flag (the
     * card's CSS class) and the subtitle with the genre read from it; the rest, the status and the inferred type among them,
     * falls back to the overview only when the detail page did not supply it.
     *
     * **The artists follow the final type.** The overview builds them from the inferred type, so a
     * night the category calls a party would keep its title as a fake act (#1950); they are rebuilt
     * whenever the category decides the type.
     */
    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent =
        primary.withGapsFrom(fallback).copy(
            // The detail page has no roster: the artists come from the card's title and subtitle, typed
            // by the category when the page has one.
            artists =
                primary.eventType
                    ?.let { buildArtistsForEventType(fallback.title, fallback.subtitle, it) }
                    ?: primary.artists.ifEmpty { fallback.artists }
        )
}

val BADEHAUS_LIMITATIONS =
    VenueLimitations(
        EventSource.BADEHAUS,
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the venue publishes no roster; for a concert the title is taken as the act and a Support: subtitle as the rest"
        ),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the venue prints no figure, and where it names money at all it is a donation range the model has no field for, kept verbatim as the note"
        ),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the subtitle names a style on some nights and is prose or a tour name on others, so about four in ten nights carry no genre"
        ),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue credits itself as the promoter on its own nights, so the stored promoter is the venue")
    )
