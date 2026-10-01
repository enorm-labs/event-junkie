package de.norm.events.scraper.kater

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for Kater Berlin's homepage programme.
 *
 * WordPress with an `event` post type, but neither structured source is usable: the REST route
 * (`/wp-json/wp/v2/event`) returns an **empty `acf` object** because the venue does not expose
 * its custom fields, leaving only id, title and permalink; and the `/event/<slug>` pages render
 * nothing but a heading. The homepage carries the entire programme inline: [HtmlFetcher]
 * fetches it conditionally, [KaterOverviewPageScraper] parses every `article.event`.
 *
 * @see KaterOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.katerclub.de/">Kater Berlin</a>
 */
@Component
class KaterWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Kater", KaterOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.KATER
    override val listsWholeProgramme: Boolean = true
}

val KATER_LIMITATIONS =
    VenueLimitations(
        EventSource.KATER,
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the club has no category field; only an unambiguous title keyword overrides the party default"),
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the per-event page carries nothing the homepage listing lacks"),
        AcceptedLimitation(
            LimitedAspect.PRICE,
            "the club sells at the door and prints no figure; a night is flagged free only when its title or blurb says so"
        ),
        AcceptedLimitation(LimitedAspect.IMAGE, "the venue prints a flyer on almost no night; the programme is text with a Resident Advisor link"),
        houseGenre = "Techno, House"
    )
