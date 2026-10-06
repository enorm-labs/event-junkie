package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.jsoup.nodes.Document

private val logger = KotlinLogging.logger {}

/**
 * The page in another language that a venue publishes beside the one an importer reads (ADR-026
 * rule 2, #330). [hreflang] names it, as the page's own `hreflang` link does. [description] reads
 * the event text from that page, so the importer stores it as [ScrapedEvent.descriptionAlt].
 */
class SecondLanguagePage(
    val hreflang: String,
    val description: (Document) -> String?
)

/**
 * The URL of this page in [hreflang], from a `<link rel="alternate">` or a language-switch link,
 * or null when the page names none or names itself. `de` also matches a region, as `de-DE`.
 */
fun Document.alternateLanguageUrl(hreflang: String): String? =
    select("link[rel=alternate][href], a[href]")
        .asSequence()
        .filter { it.attr("hreflang").let { tag -> tag.equals(hreflang, ignoreCase = true) || tag.startsWith("$hreflang-", ignoreCase = true) } }
        .map { it.absUrl("href") }
        .firstOrNull { it.isNotBlank() && it != location() }

/**
 * [url] read by [parse], or null when the fetch or the parse fails. A second language is an extra:
 * a failure logs one `WARN` and the run keeps the first language, so the upsert stores no second
 * language for those rows this run.
 */
@Suppress("TooGenericExceptionCaught") // Intentional: a missing second language must not fail the import
suspend fun <T : Any> HtmlFetcher.readSecondLanguagePage(
    url: String,
    parse: (Document) -> T?
): T? =
    try {
        val document = fetchDocument(url)
        withContext(LogContext.forPage(url)) { parse(document) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.at(Level.WARN) {
            message = "Failed to read the second-language page, keeping the first language"
            cause = e
            payload = mapOf(LogFields.URL to url)
        }
        null
    }

/**
 * Each event with [alternates] for its [key] as [ScrapedEvent.descriptionAlt]: only where the event
 * has a description, the alternate differs from it, and the scraper set no second language itself.
 * The upsert stores it only where the two read as German and English.
 */
fun <K> List<ScrapedEvent>.withDescriptionAlts(
    alternates: Map<K, String?>,
    key: (ScrapedEvent) -> K
): List<ScrapedEvent> = map { it.withDescriptionAlt(alternates[key(it)]) }

/** This event with [alt] as its second language, on the terms of [withDescriptionAlts]. */
fun ScrapedEvent.withDescriptionAlt(alt: String?): ScrapedEvent {
    val text = alt?.takeIf { it.isNotBlank() && it != description }
    return if (description == null || descriptionAlt != null || text == null) this else copy(descriptionAlt = text)
}

/**
 * The listing in another language that a venue publishes beside the one an importer reads, for a
 * venue that translates the event text on the listing itself. [descriptions] reads each card's
 * text from that page, keyed by the `sourceId` the importer gives it.
 */
class SecondLanguageListing(
    val hreflang: String,
    val descriptions: (Document) -> Map<String, String?>
)

/**
 * [events] with the second language read from the [secondLanguage] version of the listing
 * [document], when the listing links one. One more page per run, and no request per event.
 */
suspend fun HtmlFetcher.withSecondLanguageListing(
    events: List<ScrapedEvent>,
    document: Document,
    secondLanguage: SecondLanguageListing
): List<ScrapedEvent> {
    val alternates =
        document.alternateLanguageUrl(secondLanguage.hreflang)?.let { readSecondLanguagePage(it, secondLanguage.descriptions) } ?: return events
    val matched = events.withDescriptionAlts(alternates, ScrapedEvent::sourceId)
    logger.info { "Read ${alternates.size} event text(s) from the ${secondLanguage.hreflang} listing, ${matched.count { it.descriptionAlt != null }} differ" }
    return matched
}
