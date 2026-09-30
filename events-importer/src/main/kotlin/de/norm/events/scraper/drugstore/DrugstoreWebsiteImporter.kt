package de.norm.events.scraper.drugstore

import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.radar.RadarGroupImporter
import org.springframework.stereotype.Component

/**
 * Drugstore's programme, from its radar.squat.net group (node 1680). Its own site posts a dated
 * blog entry now and then; radar holds the current programme. A description names the bands in
 * prose of no fixed shape ("CAYENNE + SEITENSPRUNG AM DIENSTAG", "Featuring: Finalizer (Hardcore)
 * + …"), so no one is billed.
 */
@Component
class DrugstoreWebsiteImporter(
    apiClient: ApiClient
) : RadarGroupImporter(apiClient) {
    override val eventSource: EventSource = EventSource.DRUGSTORE
    override val venueName: String = "Drugstore"
}

val DRUGSTORE_LIMITATIONS =
    VenueLimitations(
        EventSource.DRUGSTORE,
        AcceptedLimitation(LimitedAspect.ARTISTS, "the bands are named only in free prose of no fixed shape"),
        AcceptedLimitation(LimitedAspect.GENRE, "radar files the nights under concert or party, and the style is in prose"),
        AcceptedLimitation(LimitedAspect.IMAGE, "radar serves its poster files behind an anti-bot wall, which we do not pass"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the youth centre sells no tickets online"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "radar gives one time per night, and the prose names the doors where it differs")
    )
