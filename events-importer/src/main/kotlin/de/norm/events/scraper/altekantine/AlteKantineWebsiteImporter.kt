package de.norm.events.scraper.altekantine

import de.norm.events.scraper.AbstractTwoPageWebsiteImporter
import de.norm.events.scraper.AcceptedLimitation
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HtmlFetcher
import de.norm.events.scraper.LimitedAspect
import de.norm.events.scraper.VenueLimitations
import de.norm.events.scraper.withQueryParameter
import org.jsoup.nodes.Document
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * Website importer for Alte Kantine (Kulturbrauerei) Berlin's WordPress programme.
 *
 * The WP REST API is locked down (iThemes Security returns 401 for anonymous reads), so two
 * HTML pages:
 * 1. [HtmlFetcher] fetches the homepage conditionally (ETag / Last-Modified).
 * 2. [AlteKantineOverviewPageScraper] parses the Content Views grid — discovery list, date,
 * start time, title and act line — on every page of it ([nextOverviewPage]).
 * 3. Each `?p=<id>` post via [AlteKantineDetailPageScraper] — kind, price, description, image and DJ.
 *
 * @see AlteKantineOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see AlteKantineDetailPageScraper for detail parsing (kind, price, image, DJ).
 * @see <a href="https://alte-kantine.eu/">Alte Kantine</a>
 */
@Component
class AlteKantineWebsiteImporter(
    htmlFetcher: HtmlFetcher,
    /** Clock for year inference on the year-less dates; override in tests. */
    clock: Clock = Clock.systemDefaultZone()
) : AbstractTwoPageWebsiteImporter(htmlFetcher, AlteKantineOverviewPageScraper(clock)::scrape, AlteKantineDetailPageScraper(clock)::scrape) {
    override val eventSource: EventSource = EventSource.ALTE_KANTINE
    override val listsWholeProgramme: Boolean = true

    /**
     * The grid shows ten events a page. Its paginator states the current and total page numbers,
     * and each later page renders server-side at `?_page=N`.
     */
    override fun nextOverviewPage(
        document: Document,
        url: String
    ): String? {
        val paginator = document.selectFirst(PAGINATOR_SELECTOR)
        val current = paginator?.attr("data-currentpage")?.toIntOrNull()
        val total = paginator?.attr("data-totalpages")?.toIntOrNull()
        return if (current != null && total != null && current < total) url.withQueryParameter(PAGE_PARAMETER, current + 1) else null
    }

    private companion object {
        const val PAGINATOR_SELECTOR = "ul.pt-cv-pagination"
        const val PAGE_PARAMETER = "_page"
    }
}

val ALTE_KANTINE_LIMITATIONS =
    VenueLimitations(
        EventSource.ALTE_KANTINE,
        AcceptedLimitation(LimitedAspect.PRICE, "some nights leave the Eintritt field empty (\"Eintritt: €\"), so no figure is published")
    )
