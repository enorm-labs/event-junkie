package de.norm.events.scraper.abstand

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * Abstand's programme, from its radar.squat.net group (node 1608). The bar has no website of its
 * own. A description names the bands in prose of no fixed shape ("Konzert mit Accion Mutante
 * (Crust legends Stuttgart), Necromorph …"), so no one is billed.
 */
@Component
class AbstandWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.ABSTAND
    override val venueName: String = "Abstand"
}

val ABSTAND_LIMITATIONS =
    VenueLimitations(
        setOf(EventSource.ABSTAND),
        listOf(
            AcceptedLimitation(LimitedAspect.ARTISTS, "the bands are named only in free prose of no fixed shape"),
            AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass"),
            AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets online"),
            AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night")
        ),
        houseGenre = "Punk"
    )
