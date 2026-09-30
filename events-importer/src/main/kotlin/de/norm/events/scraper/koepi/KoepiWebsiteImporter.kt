package de.norm.events.scraper.koepi

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarEvent
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * KØPI's programme, from its radar.squat.net group (node 13). koepi137.net carries statements and
 * news only, so radar is where the venue publishes its concerts. The bill is read from each
 * description by [parseKoepiBill].
 */
@Component
class KoepiWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.KOEPI
    override val venueName: String = "KØPI"

    override fun lineup(event: RadarEvent): Lineup = parseKoepiBill(event.description)
}

val KOEPI_LIMITATIONS =
    VenueLimitations(
        EventSource.KOEPI,
        AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the venue sells no tickets online"),
        AcceptedLimitation(LimitedAspect.PRICE, "radar carries no price for its concerts"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night")
    )
