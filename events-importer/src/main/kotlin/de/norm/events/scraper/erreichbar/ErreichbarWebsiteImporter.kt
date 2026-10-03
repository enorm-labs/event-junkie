package de.norm.events.scraper.erreichbar

import de.norm.events.event.EventType
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarEvent
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * Erreichbar's programme, from its radar.squat.net group (node 6653). The bar has no website of its
 * own. Its music row is the Punkrocktresen every second Thursday; the brunches and the Wednesday
 * Kufa are food and bar rows, which the radar reader leaves out. Radar files the Punkrocktresen as
 * a concert, but it is a bar night with records and a kicker, so it is stored as a party.
 */
@Component
class ErreichbarWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.ERREICHBAR
    override val venueName: String = "Erreichbar"

    override fun eventType(event: RadarEvent): String = EventType.PARTY.name
}

val ERREICHBAR_LIMITATIONS =
    VenueLimitations(
        EventSource.ERREICHBAR,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the punk bar night plays records and bills no one"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the bar posts no image to radar"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night"),
        AcceptedLimitation(LimitedAspect.PRICE, "the bar names no entry price"),
        houseGenre = "Punk"
    )
