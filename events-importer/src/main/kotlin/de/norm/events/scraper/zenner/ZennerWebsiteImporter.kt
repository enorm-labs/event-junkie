package de.norm.events.scraper.zenner

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.gatsbyPageDataUrl
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
    apiClient: ApiClient
) : AbstractJsonApiImporter(apiClient, "Zenner", ZennerApiScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ZENNER
    override val listsWholeProgramme: Boolean = true

    override fun requestUrl(url: String): String = gatsbyPageDataUrl(url)
}

val ZENNER_LIMITATIONS =
    VenueLimitations(
        EventSource.ZENNER,
        AcceptedLimitation(LimitedAspect.PRICE, "the site prints no entry price", verified = "2026-10-27"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the venue publishes no doors times", verified = "2026-08-25"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the venue publishes no sold-out state", verified = "2026-08-25"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue publishes no per-event pages", verified = "2026-10-02"),
        AcceptedLimitation(
            LimitedAspect.PROMOTERS,
            "a collective is named only in the title, and read only where the title marks its host: hosted by, presents, invites, meets",
            verified = "2026-11-15",
            issue = 5080
        ),
        houseGenre = "Electronic"
    )
