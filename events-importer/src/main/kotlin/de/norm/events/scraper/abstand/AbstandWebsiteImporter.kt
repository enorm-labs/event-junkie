package de.norm.events.scraper.abstand

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarEvent
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * Abstand's programme, from its radar.squat.net group (node 1608). The bar has no website of its
 * own. A description names the bands in prose, each with a bracket after it, which [parseAbstandBill]
 * reads. A title that names only the format (`Konzert`) is replaced by the bill.
 */
@Component
class AbstandWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.ABSTAND
    override val venueName: String = "Abstand"

    override fun lineup(event: RadarEvent): Lineup = parseAbstandBill(event.description)

    override fun title(
        event: RadarEvent,
        lineup: Lineup
    ): String {
        val bands = lineup.artists.filter { it.role != ArtistRole.DJ }
        return if (event.title.trim().lowercase() in FORMAT_TITLES && bands.isNotEmpty()) bands.joinToString(" + ") { it.name } else event.title
    }

    private companion object {
        /** Titles that name the format and no act. */
        val FORMAT_TITLES = setOf("konzert", "konzi", "concert")
    }
}

val ABSTAND_LIMITATIONS =
    VenueLimitations(
        setOf(EventSource.ABSTAND),
        listOf(
            AcceptedLimitation(LimitedAspect.ARTISTS, "the bands are named only in prose, and a name without a bracket after it is not read"),
            AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass"),
            AcceptedLimitation(LimitedAspect.TICKET_URL, "the bar sells no tickets online"),
            AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night"),
            AcceptedLimitation(LimitedAspect.PRICE, "radar lists no price on most nights"),
            AcceptedLimitation(LimitedAspect.PROMOTERS, "radar names no promoter")
        ),
        houseGenre = "Punk"
    )
