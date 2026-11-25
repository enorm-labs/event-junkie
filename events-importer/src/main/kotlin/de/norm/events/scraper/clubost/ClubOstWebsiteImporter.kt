package de.norm.events.scraper.clubost

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Club OST: the homepage alone, which carries the whole programme as JSON.
 *
 * @see ClubOstHomePageScraper for the rows.
 * @see <a href="https://clubost.de/">Club OST homepage</a>
 */
@Component
class ClubOstWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Club OST", ClubOstHomePageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.CLUB_OST
    override val listsWholeProgramme: Boolean = true
}

val CLUB_OST_LIMITATIONS =
    VenueLimitations(
        EventSource.CLUB_OST,
        AcceptedLimitation(
            LimitedAspect.DESCRIPTION,
            "the programme data has no description; its `presstext` holds the Resident Advisor link",
            verified = "2026-11-25"
        ),
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the programme data has no category",
            verified = "2026-11-25"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the programme data has no genre; every night takes the club's Techno default", verified = "2026-11-25"),
        AcceptedLimitation(LimitedAspect.PRICE, "the programme data has no price; tickets are sold on Resident Advisor", verified = "2026-11-25"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the programme data has a start and an end per night and no doors time", verified = "2026-11-25"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-11-25"),
        houseGenre = "Techno"
    )
