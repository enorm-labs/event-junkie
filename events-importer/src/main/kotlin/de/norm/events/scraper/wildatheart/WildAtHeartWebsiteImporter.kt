package de.norm.events.scraper.wildatheart

import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Wild at Heart's retro `concerts.php` programme page.
 *
 * The whole programme is one hand-coded page with no detail pages, so a single fetch:
 * [HtmlFetcher] fetches `concerts.php` conditionally (ETag / Last-Modified),
 * [WildAtHeartOverviewPageScraper] parses it. The configured source URL must point at
 * `concerts.php` (the `wah.htm` frameset and its `main.htm` welcome frame carry no event data
 * — only the `topics.htm` nav frame links to the programme).
 *
 * @see WildAtHeartOverviewPageScraper for the HTML parsing logic.
 * @see <a href="https://www.wildatheartberlin.de/concerts.php">Wild at Heart programme</a>
 */
@Component
class WildAtHeartWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for the scraper's weekday-based year inference; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Wild at Heart", WildAtHeartOverviewPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.WILD_AT_HEART
    override val listsWholeProgramme: Boolean = true
}

val WILD_AT_HEART_LIMITATIONS =
    VenueLimitations(
        EventSource.WILD_AT_HEART,
        AcceptedLimitation(LimitedAspect.PER_EVENT_PAGE, "the whole programme is one hand-coded page"),
        AcceptedLimitation(LimitedAspect.EVENT_TYPE, "the retro page has no category field; a live-music venue, so an unmarked title defaults to a concert"),
        AcceptedLimitation(
            LimitedAspect.START_TIME,
            "a start appears only inside a banner (Beginn 21:00, ab 14 Uhr); a row without one stores the house doors from info.htm (20:00), no start"
        ),
        AcceptedLimitation(LimitedAspect.PRICE, "the venue prints no ticket price; it marks only free-entry nights and links a few shows to a shop")
    )
