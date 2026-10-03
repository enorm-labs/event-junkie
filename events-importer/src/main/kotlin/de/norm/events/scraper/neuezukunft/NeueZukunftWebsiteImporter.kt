package de.norm.events.scraper.neuezukunft

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Neue Zukunft Berlin.
 *
 * The public site (`neue-zukunft.org`) is a static landing page whose programme is published
 * only as an image-based monthly PDF poster and an embedded Elfsight "Event Calendar" widget
 * rendered client-side. Neither is scrapeable as HTML, but the widget's public boot API returns
 * every event as clean JSON — the most stable source (ADR-007 §"Selector Strategy" — structured
 * data is priority 1), no headless browser needed:
 * 1. [ApiClient.fetchJson] fetches the boot JSON (shared politeness throttle and User-Agent).
 * The configured `url` is the boot endpoint with the widget id
 * (`core.service.elfsight.com/p/boot/?w=<widgetId>`), used verbatim — all events come back in
 * one response (ADR-007 first-page-only).
 * 2. [NeueZukunftApiScraper] parses it into [de.norm.events.scraper.ScrapedEvent]s.
 *
 * The boot API sends no ETag / Last-Modified, so `etag` / `lastModified` are ignored and every
 * import returns [ImportResult.Success] (never [ImportResult.NotModified]). Re-imports stay
 * cheap and safe because persistence upserts idempotently by `sourceId`.
 *
 * @see NeueZukunftApiScraper for the JSON parsing logic.
 * @see <a href="https://neue-zukunft.org/">Neue Zukunft</a>
 */
@Component
class NeueZukunftWebsiteImporter(
    apiClient: ApiClient,
    /** Clock anchoring the rolling recurrence horizon; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractJsonApiImporter(apiClient, "Neue Zukunft", { json, _ -> NeueZukunftApiScraper(clock).scrape(json) }) {
    override val eventSource: EventSource = EventSource.NEUE_ZUKUNFT
    override val listsWholeProgramme: Boolean = true
}

val NEUE_ZUKUNFT_LIMITATIONS =
    VenueLimitations(
        EventSource.NEUE_ZUKUNFT,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the calendar widget exposes no per-event URLs"),
        AcceptedLimitation(LimitedAspect.PRICE, "the calendar widget prints no figure; each show links out to an external ticket shop"),
        AcceptedLimitation(
            LimitedAspect.GENRE,
            "the calendar's categories are rooms (Saal, Garage, Jazzbar) and its tags are blank; every music night takes the house's Psychedelic"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "the calendar widget sets no cover image on upcoming shows"),
        houseGenre = "Psychedelic"
    )
