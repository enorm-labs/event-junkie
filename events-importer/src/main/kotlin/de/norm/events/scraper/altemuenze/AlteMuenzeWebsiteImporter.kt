package de.norm.events.scraper.altemuenze

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for the Alte Münze cultural quarter on the Molkenmarkt, over the venue's **The Events Calendar** REST API.
 *
 * The programme is a handful of events a month, so the walk is one page; [MAX_PAGES] bounds the plugin's `next_rest_url` cursor.
 * The API sends no validators, and the upserts are idempotent by `sourceId`. The club MUENZE in the same building publishes
 * its nights elsewhere; this calendar lists the quarter's own exhibitions, festivals and performances.
 * The API filters on the start, so a running exhibition leaves the response on its opening day; the row stored before it stays.
 *
 * @see AlteMuenzeApiScraper for the field mapping and the out-of-scope categories.
 * @see <a href="https://www.alte-muenze-berlin.de/programm/">Alte Münze programme</a>
 */
@Component
class AlteMuenzeWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "Alte Münze", AlteMuenzeApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.ALTE_MUENZE

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val ALTE_MUENZE_LIMITATIONS =
    VenueLimitations(
        EventSource.ALTE_MUENZE,
        AcceptedLimitation(LimitedAspect.PRICE, "most events set no cost"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the event link is often an artist's site or a social profile, not a shop"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "the calendar bills no lineup; the acts are in the prose"),
        AcceptedLimitation(LimitedAspect.GENRE, "the tags mix styles with formats and topics")
    )
