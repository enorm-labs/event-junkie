package de.norm.events.scraper.humboldtforum

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Humboldt Forum. `/de/programm/` draws its dates in the browser from the theme's
 * `/wp-json/humboldt/v1/event/items?lang=de`, which answers every current item in one response, without a key and with no
 * paging. The response is about 1.6 MB for some 300 items, and tours and workshops are about 40 % of them.
 *
 * The Christmas market is the category `Markt` and stays out: it is entered by hand (EVENT_SCOPE.md §3.7).
 */
@Component
class HumboldtForumWebsiteImporter(
    apiClient: ApiClient
) : AbstractJsonApiImporter(apiClient, "Humboldt Forum", HumboldtForumApiScraper()::scrape) {
    override val eventSource: EventSource = EventSource.HUMBOLDT_FORUM
}

val HUMBOLDT_FORUM_LIMITATIONS =
    VenueLimitations(
        EventSource.HUMBOLDT_FORUM,
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "the API names no act for a reading, a talk or a show",
            verified = "2026-11-15"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name a format, not a music style", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the API gives one start time per date", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the API gives one price line, with the concessions in it", verified = "2026-11-15"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the house programmes every item itself and names no partner field", verified = "2026-11-15")
    )
