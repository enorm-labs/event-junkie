package de.norm.events.scraper.admiralspalast

import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for Admiralspalast's production listings.
 *
 * This scraper is deliberately **not** a `*OverviewPageScraper` returning events: the A–Z listing at
 * `/veranstaltungsuebersicht.html` carries no schedule at all. Every one of its tiles renders the
 * same `ab DD.MM.YY` run-start date, so the only thing worth taking from it is the set of
 * `/veranstaltung/<slug>.html` production links — the pages that actually list the performances.
 *
 * The same markup backs the `/veranstaltungsuebersicht/eventkategorie/<genre>.html` filter pages, so
 * one parser serves both: the A–Z page for discovery, and each genre page for the category it names.
 *
 * @see AdmiralspalastDetailPageScraper for the performances themselves.
 * @see AdmiralspalastWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://www.admiralspalast.theater/veranstaltungsuebersicht.html">Admiralspalast programme</a>
 */
class AdmiralspalastListingPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Reads the production links from a listing page, in listing order and without duplicates.
     *
     * A tile links to its production several times over (poster, title, date), so the raw selector
     * yields roughly three hits per production.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve the links.
     */
    fun scrapeProductionUrls(
        document: Document,
        baseUrl: String
    ): List<String> {
        val urls =
            document
                .select("a[href*=$PRODUCTION_PATH_PREFIX]")
                .mapNotNull { link -> link.attr("href").takeIf { it.isNotBlank() } }
                .map { resolveUrl(baseUrl, it) }
                .map { it.substringBefore('#') }
                .distinct()

        logger.info { "Found ${urls.size} production link(s) on the Admiralspalast listing at $baseUrl" }
        return urls
    }

    /**
     * Reads the genre-filter links, mapping each category slug to the label the venue prints.
     *
     * The venue states a category nowhere on the event itself — the filter pages are the only place
     * it exists, so the importer walks them to type its events.
     */
    fun scrapeGenreUrls(
        document: Document,
        baseUrl: String
    ): Map<String, String> =
        document
            .select("a[href*=$GENRE_PATH_PREFIX]")
            .mapNotNull { link ->
                val href = link.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = link.text().trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
                resolveUrl(baseUrl, href).substringBefore('#') to label
            }.toMap()

    private companion object {
        /** Path prefix of a production page — the page that lists its performances. */
        const val PRODUCTION_PATH_PREFIX = "/veranstaltung/"

        /** Path prefix of a category-filtered copy of the listing. */
        const val GENRE_PATH_PREFIX = "/veranstaltungsuebersicht/eventkategorie/"
    }
}
