package de.norm.events.scraper.kulturhausspandau

import de.norm.events.scraper.AbstractJsonApiImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Kulturhaus Spandau. The `/programm/` HTML holds only teasers, and the theme's script reads the list
 * from `/wp-json/eventmanager/v1/events`, which answers without a key, in one response and with no paging parameter.
 *
 * The response stops at 250 events, the oldest from 2022. It reaches further ahead than the house plans today, but it is a cap,
 * so the import does not claim to hold the whole programme.
 */
@Component
class KulturhausSpandauWebsiteImporter(
    apiClient: ApiClient
) : AbstractJsonApiImporter(apiClient, "Kulturhaus Spandau", KulturhausSpandauApiScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KULTURHAUS_SPANDAU
}

val KULTURHAUS_SPANDAU_LIMITATIONS =
    VenueLimitations(
        EventSource.KULTURHAUS_SPANDAU,
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the API carries no event text, and the event page is a teaser"),
        AcceptedLimitation(LimitedAspect.GENRE, "the categories name a format, not a music style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the API gives one start time per event"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the API gives one admission price and the concessions, no box-office price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the `organizer_id` is empty on every event")
    )
