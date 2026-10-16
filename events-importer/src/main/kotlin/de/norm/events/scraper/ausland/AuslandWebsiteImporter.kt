package de.norm.events.scraper.ausland

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.SecondLanguagePage
import de.norm.events.scraper.VenueLimitations
import org.springframework.stereotype.Component

/**
 * Website importer for ausland, a TYPO3 site: `/program/all` lists every upcoming event on one page, then each event's
 * own page adds the text, the doors time and the price note.
 *
 * The site is English first. Each event page links its German version (`hreflang="de-DE"`), which translates the text,
 * so that page is read too for the second language: one more request per event.
 *
 * A concert night's listing clock is its doors time, so a page that states doors but no start keeps only the doors.
 *
 * @see AuslandOverviewPageScraper for the listing.
 * @see AuslandEventPageScraper for the event pages.
 */
@Component
class AuslandWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, AuslandOverviewPageScraper()::scrape, AuslandEventPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.AUSLAND
    override val listsWholeProgramme: Boolean = true
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.DESCRIPTION, ScrapedField.START_TIME, ScrapedField.PRICES)
    override val secondLanguage = SecondLanguagePage("de", AuslandEventPageScraper()::description)

    override fun fillGapsFromOverview(
        primary: ScrapedEvent,
        fallback: ScrapedEvent
    ): ScrapedEvent {
        val merged = primary.withGapsFrom(fallback)
        return if (merged.doorsTime != null && merged.startTime == merged.doorsTime) merged.copy(startTime = null) else merged
    }
}

val AUSLAND_LIMITATIONS =
    VenueLimitations(
        EventSource.AUSLAND,
        AcceptedLimitation(
            LimitedAspect.EVENT_TYPE,
            "the venue names no category, so a night without a format word in its title is a concert, which mislabels a performance or a talk"
        ),
        AcceptedLimitation(LimitedAspect.GENRE, "the site names no musical style"),
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "only concert nights state a doors time; the other formats give one clock"),
        AcceptedLimitation(LimitedAspect.END_DATE, "a residency, workshop or installation over several days is listed once, on its first day"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the listing gives one clock per event, and an end only appears in some texts"),
        AcceptedLimitation(LimitedAspect.PRICE, "a price appears only as prose in some event texts, kept as a note; the rest state none"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "most nights sell only at the door; a ticket shop is linked only from a phrase in the text"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site credits partners only as logos"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status")
    )
