package de.norm.events.scraper.houseofmusic

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for House of Music: the home page alone, whose Wix Events widget holds every event.
 *
 * @see HouseOfMusicOverviewPageScraper for the warmup payload.
 */
@Component
class HouseOfMusicWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "House of Music", HouseOfMusicOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.HOUSE_OF_MUSIC
    override val listsWholeProgramme: Boolean = true
}

val HOUSE_OF_MUSIC_LIMITATIONS =
    VenueLimitations(
        EventSource.HOUSE_OF_MUSIC,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme names one time per event"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the venue hides the end on most events"),
        AcceptedLimitation(LimitedAspect.PRICE, "every event sells its tickets elsewhere, and the payload carries no price"),
        AcceptedLimitation(LimitedAspect.IMAGE, "the widget shows no images, and the events carry none"),
        AcceptedLimitation(LimitedAspect.DESCRIPTION, "the events carry no text"),
        AcceptedLimitation(LimitedAspect.GENRE, "the payload carries no category, and the titles name no style"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the venue hides each event's own page")
    )
