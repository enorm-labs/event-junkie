package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document
import kotlin.time.Duration.Companion.seconds

/**
 * The one fetch orchestration behind [AbstractSinglePageWebsiteImporter], [AbstractTwoPageWebsiteImporter]
 * and [AbstractMonthPagesWebsiteImporter] (#4643). Each base describes its run as an [HtmlImport]; this
 * runs the steps in one order: entry page, listing, second-language listing, event pages, shared pages,
 * fold, undated drop. A step a base leaves out is skipped. Every base gets the dateless-row retry and
 * the undated drop.
 *
 * [logger] is the base's own, so every line still names the concrete importer.
 */
internal class HtmlImportPipeline(
    private val htmlFetcher: HtmlFetcher,
    private val logger: KLogger
) {
    suspend fun run(
        import: HtmlImport,
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val entry =
            when (val mode = import.entry) {
                is HtmlImport.Entry.Conditional -> {
                    when (val fetched = htmlFetcher.fetch(url, etag, lastModified, mode.cookies)) {
                        is FetchResult.NotModified -> return ImportResult.NotModified
                        is FetchResult.Success -> fetched
                    }
                }

                HtmlImport.Entry.Unconditional -> {
                    FetchResult.Success(htmlFetcher.fetchDocument(url), etag = null, lastModified = null)
                }
            }
        val listing = import.readListing(entry.document, url)
        import.listedLine?.let { line -> logger.info { line(listing.events.size) } }

        val translated =
            import.secondLanguageListing?.let { htmlFetcher.withSecondLanguageListing(listing.events, entry.document, it) } ?: listing.events
        val enriched = import.eventPages?.let { readEventPages(translated, it) } ?: translated
        val folded = import.foldRuns(htmlFetcher.withSharedPages(enriched, import.sharedPages))
        val events = dropUnresolvedDates(folded)
        import.doneLine?.let { line -> logger.info { line(events.size, listing.pages) } }

        return ImportResult.Success(
            events = events,
            etag = entry.etag,
            lastModified = entry.lastModified,
            droppedUnresolvedDate = folded.size - events.size,
            complete = listing.complete
        )
    }

    private suspend fun readEventPages(
        events: List<ScrapedEvent>,
        pages: HtmlImport.EventPages
    ): List<ScrapedEvent> =
        when (pages) {
            is HtmlImport.EventPages.Enrich -> events.map { if (pages.hasEventPage(it)) enrichOrFlagged(it, pages) else it }
            is HtmlImport.EventPages.Detail -> events.map { parseDetailOrFallback(it, pages) }
        }

    /**
     * [event] enriched from its own page, or flagged as [withEventPageOrFlagged] flags it. A row with
     * no date without its page gets a second read after [RETRY_PAUSE], as a detail page does. With
     * [HtmlImport.EventPages.Enrich.secondLanguage], the page's version in that language supplies the
     * second language, only where the page changed the event text: a listing teaser has no
     * counterpart there.
     */
    private suspend fun enrichOrFlagged(
        event: ScrapedEvent,
        pages: HtmlImport.EventPages.Enrich
    ): ScrapedEvent {
        val attempts = if (event.eventDate == UNRESOLVED_EVENT_DATE) DATELESS_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            val read =
                htmlFetcher.readEventPage(event) { document ->
                    pages.enrich(event, document)?.let {
                        EnrichedPage(
                            it,
                            pages.secondLanguage?.let { language ->
                                document.alternateLanguageUrl(language.hreflang)
                            }
                        )
                    }
                }
            if (read != null) return read.withSecondLanguage(event, pages.secondLanguage)
            if (attempt < attempts - 1) delay(RETRY_PAUSE)
        }
        return event.degraded(pages.owns)
    }

    private suspend fun EnrichedPage.withSecondLanguage(
        listed: ScrapedEvent,
        language: SecondLanguagePage?
    ): ScrapedEvent {
        if (language == null || alternateUrl == null || event.description == listed.description) return event
        return event.withDescriptionAlt(htmlFetcher.readSecondLanguagePage(alternateUrl, language.description))
    }

    /** One event as its page enriched it, and the URL of that page's version in the second language. */
    private class EnrichedPage(
        val event: ScrapedEvent,
        val alternateUrl: String?
    )

    /**
     * Drops events whose date is still the [UNRESOLVED_EVENT_DATE] sentinel after the merge: neither
     * the listing nor the event page supplied a real date (a dateless featured teaser whose detail page
     * was unavailable). Persisting these would produce a garbage slug and date, so they are discarded
     * with a warning rather than stored.
     */
    private fun dropUnresolvedDates(events: List<ScrapedEvent>): List<ScrapedEvent> {
        val (resolved, unresolved) = events.partition { it.eventDate != UNRESOLVED_EVENT_DATE }
        unresolved.forEach { event ->
            logger.at(Level.WARN) {
                message = "Dropping '${event.title}': no event date resolved from overview or detail page"
                payload = mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
            }
        }
        return resolved
    }

    /**
     * Degrades to the overview row when the detail page is unavailable — except for a row that has
     * no date without it (a Kulturhäuser featured teaser, #312), which is dropped rather than
     * degraded, so that one gets a second fetch after [RETRY_PAUSE] before the run gives up on it.
     * A degraded row is marked [ScrapedEvent.detailUnavailable], so the upsert keeps the fields the
     * detail page stored last time.
     */
    @Suppress("TooGenericExceptionCaught") // Intentional: degrade to overview data if detail page is unavailable
    private suspend fun parseDetailOrFallback(
        overview: ScrapedEvent,
        detail: HtmlImport.EventPages.Detail
    ): ScrapedEvent {
        val attempts = if (overview.eventDate == UNRESOLVED_EVENT_DATE) DATELESS_ATTEMPTS else 1
        repeat(attempts) { attempt ->
            try {
                return mergeDetail(overview, detail)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val last = attempt == attempts - 1
                logger.at(Level.WARN) {
                    message =
                        if (last) {
                            "Failed to fetch detail page for '${overview.title}', using overview data"
                        } else {
                            "Failed to fetch detail page for '${overview.title}', which has no date without it; retrying once"
                        }
                    cause = e
                    payload = mapOf(LogFields.URL to overview.sourceUrl, LogFields.EVENT_SOURCE_ID to overview.sourceId)
                }
                if (!last) delay(RETRY_PAUSE)
            }
        }
        return overview.degraded(detail.owns)
    }

    private suspend fun mergeDetail(
        overview: ScrapedEvent,
        detail: HtmlImport.EventPages.Detail
    ): ScrapedEvent {
        val detailDoc = htmlFetcher.fetchDocument(overview.sourceUrl)
        // The scope opens AFTER the fetch, deliberately: the fetch already writes `url` as a
        // payload field, and a line inside both would carry the key twice (#982). Everything
        // inside is the scraper's own parsing, which is what needed the URL and never had it.
        val parsed =
            withContext(LogContext.forPage(overview.sourceUrl)) { detail.scrape(detailDoc, overview.sourceUrl) }
                ?: return overview.degraded(detail.owns)
        val merged = detail.merge(parsed, overview)
        // Only beside the detail page's own text: the listing's teaser has no counterpart there.
        return if (parsed.description == null) merged else merged.withDescriptionAlt(secondLanguageText(detailDoc, detail.secondLanguage))
    }

    /** The event text of [detailDoc]'s version in [language], or null without one. */
    private suspend fun secondLanguageText(
        detailDoc: Document,
        language: SecondLanguagePage?
    ): String? =
        language?.let {
            detailDoc.alternateLanguageUrl(it.hreflang)?.let { url -> htmlFetcher.readSecondLanguagePage(url, it.description) }
        }

    private fun ScrapedEvent.degraded(owns: Set<ScrapedField>): ScrapedEvent = copy(detailUnavailable = true, detailPageOwns = owns)

    private companion object {
        /** Fetches for a row whose date lives only on its detail page. */
        const val DATELESS_ATTEMPTS = 2

        /** Long enough for a momentary refusal to pass; short enough not to stall the run. */
        val RETRY_PAUSE = 5.seconds
    }
}

/** One run of [HtmlImportPipeline], as a base describes it. */
@Suppress("LongParameterList") // One parameter per pipeline step, each optional and named at the call site
internal class HtmlImport(
    val entry: Entry,
    /** The listing from the entry page and its URL: one page, a page walk, or month pages. */
    val readListing: suspend (Document, String) -> Listing,
    /** Logged with the listing's event count, before any event page is read. */
    val listedLine: ((Int) -> String)? = null,
    val secondLanguageListing: SecondLanguageListing? = null,
    val eventPages: EventPages? = null,
    val sharedPages: SharedPages<*>? = null,
    val foldRuns: (List<ScrapedEvent>) -> List<ScrapedEvent> = { it },
    /** Logged at the end with the event count and the number of listing pages read. */
    val doneLine: ((Int, Int) -> String)? = null
) {
    /** How the entry page is fetched: with the source's validators and cookies, or unconditionally. */
    sealed interface Entry {
        class Conditional(
            val cookies: Map<String, String> = emptyMap()
        ) : Entry

        data object Unconditional : Entry
    }

    /** What the listing read: its events, the pages it took, and whether every page loaded. */
    class Listing(
        val events: List<ScrapedEvent>,
        val complete: Boolean,
        val pages: Int = 1
    )

    /** How each event's own page is read. */
    sealed interface EventPages {
        /** The listing row enriched by [enrich], for the rows [hasEventPage] accepts, flagged as [withEventPageOrFlagged] flags it. */
        class Enrich(
            val enrich: (ScrapedEvent, Document) -> ScrapedEvent?,
            val hasEventPage: (ScrapedEvent) -> Boolean,
            val owns: Set<ScrapedField>,
            val secondLanguage: SecondLanguagePage? = null
        ) : EventPages

        /**
         * The detail page parsed whole by [scrape] and merged over the row by [merge], with one retry
         * for a dateless row and the [secondLanguage] text beside a detail description.
         */
        class Detail(
            val scrape: (Document, String) -> ScrapedEvent?,
            val merge: (ScrapedEvent, ScrapedEvent) -> ScrapedEvent,
            val owns: Set<ScrapedField>,
            val secondLanguage: SecondLanguagePage?
        ) : EventPages
    }
}
