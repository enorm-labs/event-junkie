package de.norm.events.scraper.acudmachtneu

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.VenueLimitations
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.YearMonth

/**
 * Website importer for ACUD MACHT NEU, a WordPress theme with no event type in its REST API. `/programm/` shows the
 * current month; the run reads it and the next month's `/programm/YYYY/M/` page, since the venue publishes about a month
 * ahead. Each event's own page adds the room, the time, the text and the full-size picture.
 *
 * The page's forward arrow is unreliable (October links December), so the next month is computed from the back arrow,
 * which names the month before the one shown.
 *
 * @see AcudMachtNeuOverviewPageScraper for the month pages.
 * @see AcudMachtNeuEventPageScraper for the event pages.
 */
@Component
class AcudMachtNeuWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractTwoPageWebsiteImporter(htmlFetcher, AcudMachtNeuOverviewPageScraper()::scrape, AcudMachtNeuEventPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.ACUD_MACHT_NEU
    override val detailPageOwns: Set<ScrapedField> = setOf(ScrapedField.IMAGE, ScrapedField.DESCRIPTION, ScrapedField.START_TIME)

    /** The month after the entry page's; a month page links nothing further. */
    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? {
        if (MONTH_PATH.containsMatchIn(url)) return null
        val previous = document.selectFirst("a.program-monat-nav__arrow--prev[href]")?.absUrl("href") ?: return null
        val (year, month) = MONTH_PATH.find(previous)?.destructured ?: return null
        val next = YearMonth.of(year.toInt(), month.toInt()).plusMonths(2)
        return previous.replace(MONTH_PATH, "/programm/${next.year}/${next.monthValue}/")
    }

    private companion object {
        val MONTH_PATH = Regex("""/programm/(\d{4})/(\d{1,2})/""")
    }
}

val ACUD_MACHT_NEU_LIMITATIONS =
    VenueLimitations(
        EventSource.ACUD_MACHT_NEU,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "the programme has a title per event and no second title line"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site files events only as club night, concert, performance, exhibition or workshop"),
        AcceptedLimitation(LimitedAspect.END_TIME, "the site prints one time per event and an end only in free text"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the site links a presale shop and prints only the door price"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "some pages print only a presale link and no door price"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the site names a collective only in the event text"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled event can only disappear")
    )
