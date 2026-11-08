package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging

/**
 * Base class for a venue whose programme is a list of [inputs], each read on its own by [read]: party
 * pages resolved against the source URL, the days a calendar names, the nights an overview links. A
 * failed input is skipped with [failure] and leaves the run incomplete. The run fetches without
 * validators and returns none, because its inputs are not the entry page.
 *
 * It is not a step of [HtmlImportPipeline], whose every run starts from one fetched entry document:
 * here [inputs] may read no page at all, and an input may be a POST or a script rather than an event page.
 */
abstract class AbstractFanOutImporter<I> : EventImporter {
    // javaClass.name, so the log names the concrete importer rather than this base.
    private val logger = KotlinLogging.logger(javaClass.name)

    final override val fetchesBeyondEntryPage: Boolean = true

    /** What the run reads, from the source [url]; it fetches the entry page itself when it needs one. */
    protected abstract suspend fun inputs(url: String): List<I>

    /** The events of one [input]; a throw skips it and marks the run incomplete. */
    protected abstract suspend fun read(input: I): List<ScrapedEvent>

    /** The warning logged when [input] fails. */
    protected abstract fun failure(input: I): String

    /** Logged at the end with the event count and the number of inputs. */
    protected abstract fun doneLine(
        events: Int,
        inputs: Int
    ): String

    /** The events of every input read, combined into the run's result. */
    protected open fun combine(events: List<ScrapedEvent>): List<ScrapedEvent> = events

    /** When set, the run fails with this message if every input failed, rather than return an empty run. */
    protected open val noInputReadMessage: String? = null

    final override suspend fun importEvents(
        url: String,
        etag: String?,
        lastModified: String?
    ): ImportResult {
        val inputs = inputs(url)
        val read = readEach(inputs, ::failure) { read(it) }
        noInputReadMessage?.let { message -> check(read.complete || read.items.isNotEmpty()) { message } }
        val events = combine(read.items)
        logger.info { doneLine(events.size, inputs.size) }
        return ImportResult.Success(events = events, etag = null, lastModified = null, complete = read.complete)
    }
}
