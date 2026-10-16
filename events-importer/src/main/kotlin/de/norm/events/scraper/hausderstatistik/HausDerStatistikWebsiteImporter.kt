package de.norm.events.scraper.hausderstatistik

import de.norm.events.scraper.AbstractMonthPagesWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.querySeparator
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component

/**
 * Importer for Haus der Statistik, a WordPress site whose events a Teamup calendar fills. The
 * `/programm/` listing pages by a fixed number of rows of the whole programme, so a filtered page
 * can be empty and still link a next one. Its month filter is not paged, so the run reads one
 * month page per `monat[]` radio value on the entry page ([HausDerStatistikMonthPageScraper]).
 *
 * Each month is read with `typ=veranstaltung`, which leaves out the "Regelmäßige Angebote": open
 * workshops, the garden, the radio studio. Each row's own page adds the full text, the show time
 * and the price note ([HausDerStatistikEventPageScraper]). Its organiser line is not read, because
 * it often names a private person with an e-mail address. The `/en/` pages carry the German event
 * text unchanged, so the site has no second language.
 */
@Component
class HausDerStatistikWebsiteImporter(
    htmlFetcher: HtmlFetcher
) : AbstractMonthPagesWebsiteImporter(htmlFetcher, "Haus der Statistik", MONTH_INPUT_SELECTOR, HausDerStatistikMonthPageScraper()::scrape) {
    override val eventSource: EventSource = EventSource.HAUS_DER_STATISTIK

    override val enrichFromEventPage: (ScrapedEvent, Document) -> ScrapedEvent? = HausDerStatistikEventPageScraper()::scrape

    override fun monthPageUrls(
        entry: Document,
        url: String
    ): List<String> =
        entry
            .select(MONTH_INPUT_SELECTOR)
            .map { it.attr("value") }
            .filter { MONTH.matches(it) }
            .distinct()
            .map { month -> "$url${url.querySeparator()}typ=veranstaltung&monat%5B%5D=$month" }

    private companion object {
        /** The month filter's radio buttons; "Alle" carries the value `0`. */
        const val MONTH_INPUT_SELECTOR = "form input[name='monat[]']"

        val MONTH = Regex("""\d{4}-\d{2}""")
    }
}

val HAUS_DER_STATISTIK_LIMITATIONS =
    VenueLimitations(
        EventSource.HAUS_DER_STATISTIK,
        AcceptedLimitation(LimitedAspect.SUBTITLE, "each row has a title and a teaser, no second title line"),
        AcceptedLimitation(LimitedAspect.TICKET_URL, "the event pages link no ticket shop"),
        AcceptedLimitation(LimitedAspect.GENRE, "the site tags a format such as Musik, never a style"),
        AcceptedLimitation(LimitedAspect.PRICE_PRESALE, "the site prints one free-text price line, read as the note"),
        AcceptedLimitation(LimitedAspect.PRICE_BOX_OFFICE, "the site prints one free-text price line, read as the note"),
        AcceptedLimitation(LimitedAspect.PROMOTERS, "the organiser line often names a private person with an e-mail address"),
        AcceptedLimitation(LimitedAspect.SOLD_OUT, "the site states no ticket status"),
        AcceptedLimitation(LimitedAspect.CANCELLATION, "the site has no status field, so a cancelled event can only disappear")
    )
