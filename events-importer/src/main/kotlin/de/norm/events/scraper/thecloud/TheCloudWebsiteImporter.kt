package de.norm.events.scraper.thecloud

import de.norm.events.scraper.AbstractTecImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for The Cloud, the event space on Alexanderstraße, over the venue's **The Events Calendar** REST API.
 * The `/events/` page renders the same plugin. Outside promoters write the event texts.
 *
 * The site's WordPress timezone is a fixed `UTC+1`, but `start_date` is the clock the editor typed, so it is read as
 * Berlin time; the UTC fields would shift every summer event by an hour.
 *
 * @see TheCloudApiScraper for the field mapping.
 * @see <a href="https://thecloud.berlin/events/">The Cloud events</a>
 */
@Component
class TheCloudWebsiteImporter(
    apiClient: ApiClient
) : AbstractTecImporter(apiClient, "The Cloud", TheCloudApiScraper()::scrapePage, MAX_PAGES) {
    override val eventSource: EventSource = EventSource.THE_CLOUD

    private companion object {
        /** Safety bound on the cursor walk; the programme is one page today. */
        const val MAX_PAGES = 10
    }
}

val THE_CLOUD_LIMITATIONS =
    VenueLimitations(
        EventSource.THE_CLOUD,
        AcceptedLimitation(LimitedAspect.GENRE, "every event is filed under one `Night Club` category; the style is only in the prose"),
        AcceptedLimitation(LimitedAspect.PRICE, "`cost` is empty on every event; ticket tiers appear only in the promoters' prose"),
        AcceptedLimitation(LimitedAspect.ARTISTS, "a club night names its DJs only in free prose; only a live show bills its act in the title")
    )
