package de.norm.events.scraper.drugstore

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarEvent
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * Drugstore's programme, from its radar.squat.net group (node 1680). Its own site posts a dated
 * blog entry now and then; radar holds the current programme. The bill is read from each
 * description by [parseDrugstoreBill], and the times by [parseDrugstoreTimes].
 */
@Component
class DrugstoreWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.DRUGSTORE
    override val venueName: String = "Drugstore"

    override fun lineup(event: RadarEvent): Lineup = parseDrugstoreBill(event.description)

    override fun times(event: RadarEvent): Times = parseDrugstoreTimes(event.description, event.start.toLocalTime())
}

val DRUGSTORE_LIMITATIONS =
    VenueLimitations(
        EventSource.DRUGSTORE,
        AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass", verified = "2026-09-30"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the youth centre sells no tickets online", verified = "2026-09-30"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night, and only some descriptions label the doors", verified = "2026-11-04"),
        AcceptedLimitation(LimitedAspect.PRICE, "radar lists no price on most nights", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "radar names no promoter", verified = "2026-10-13")
    )
