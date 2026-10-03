package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Base class for a venue whose entry page holds no events, only a link per month at
 * [monthLinkSelector]: fetch the entry page, read every linked month with [scrape], keep the others
 * when one fails. The entry page changes only when a month is added, so it is fetched
 * unconditionally; its validators would mask an edit inside a month.
 */
abstract class AbstractMonthPagesWebsiteImporter(
    private val htmlFetcher: HtmlFetcher,
    private val venueName: String,
    private val monthLinkSelector: String,
    private val scrape: (Document, String) -> List<ScrapedEvent>
) : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    final override val fetchesBeyondEntryPage: Boolean get() = true

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val monthUrls = htmlFetcher.fetchDocument(url).absoluteLinksAt(monthLinkSelector, url)
        logger.info { "Found ${monthUrls.size} month page(s) linked from $venueName entry $url" }

        val months =
            readEach(monthUrls, { "Failed to read the $venueName month page $it, importing the other months" }) { monthUrl ->
                scrape(htmlFetcher.fetchDocument(monthUrl), monthUrl)
            }
        val events = months.items.distinctBy { it.sourceId }
        logger.info { "Scraped ${events.size} $venueName event(s) across ${monthUrls.size} month page(s)" }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = months.complete)
    }
}
