package de.norm.events.scraper.showfenster

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.AbstractSinglePageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate

/**
 * Website importer for Showfenster Theater, a Wix site whose calendar page carries the whole programme in its warmup JSON.
 *
 * The payload holds every event, past nights included. An upcoming concert's own Wix page is read too, because its full
 * text can name the players where the calendar's title names a work (#5238). A page that fails keeps the stored lineup.
 * The Eventfrog page is another site and is not fetched.
 *
 * @see ShowfensterOverviewPageScraper for the calendar.
 * @see ShowfensterEventPageScraper for a concert's page.
 */
@Component
class ShowfensterWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** For the past-night cutoff on concert pages; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) : AbstractSinglePageWebsiteImporter(htmlFetcher, "Showfenster Theater", ShowfensterOverviewPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.SHOWFENSTER
    override val listsWholeProgramme: Boolean = true
    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = ShowfensterEventPageScraper()::enrich
    override val eventPageOwns: Set<ScrapedField> = setOf(ScrapedField.ARTISTS)

    override fun hasEventPage(event: ScrapedEvent): Boolean = event.eventType == EventType.CONCERT.name && !event.eventDate.isBefore(LocalDate.now(clock))
}

val SHOWFENSTER_LIMITATIONS =
    VenueLimitations(
        EventSource.SHOWFENSTER,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the calendar gives each event a title and one short text", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.PRICE, "prices are on the Eventfrog ticket shop, not on the venue's site", verified = "2026-10-08"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the calendar gives a start and an end time, no doors time", verified = "2026-10-08"),
        AcceptedLimitation(
            LimitedAspect.ARTISTS,
            "a concert bills the players its page lists under 'Es spielen', else its title's act, as comedy and shows do; " +
                "a reading bills a duo by surnames, and the house's formats, quizzes and the open stage name none",
            verified = "2026-11-27"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "only a concert's category names a style; comedy, readings and shows carry none", verified = "2026-10-13"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the venue presents every night itself", verified = "2026-10-13")
    )
