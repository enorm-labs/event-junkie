package de.norm.events.scraper.aeg

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SUPPORT_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.descriptionText
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.splitSupportActs
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.net.URI
import java.time.LocalTime

/**
 * Pure HTML parser for the AEG venues' event detail pages (`/events/detail/<slug>/<YYYY-MM-DD-HHMM>`).
 *
 * Adds what a listing row cannot carry: the `Einlass` doors time, the prose description, the
 * external ticket-shop link, a package show's bill from a `Line Up:` paragraph in the
 * description (see [parseLineUp]), and the acts of a `Support Act:` paragraph ([parseSupport]).
 *
 * Deliberately supplies **no date and no usable title**: `h1.summary` appends the venue ("JONY
 * **live in der Uber Eats Music Hall**"), so the listing's cleaner act name is kept, and the
 * listing already assembled the date from its span group. Both are left to
 * [AbstractAegVenueImporter.fillGapsFromOverview].
 *
 * @see AegOverviewPageScraper for the listing parser (title, date, category, price).
 * @see AbstractAegVenueImporter for the HTTP fetch orchestrator.
 */
class AegDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without an event heading — the marker
     * that the request did not return a real event page.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl] and the [ScrapedEvent.sourceId].
     * @param eventSource the venue whose page this is, for the `sourceId` prefix.
     */
    fun scrape(
        document: Document,
        sourceUrl: String,
        eventSource: EventSource
    ): ScrapedEvent? {
        val heading = document.textAt("h1.summary")
        if (heading == null) {
            logger.warn { "$eventSource detail page has no heading, skipping" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, "/events/detail/")

        return ScrapedEvent(
            // The listing owns the title: this heading carries an "in der <venue>" tail.
            title = heading,
            description = document.selectFirst(".event_body")?.descriptionText(),
            // The listing owns the date; the sentinel lets it fill this in at the merge.
            eventDate = UNRESOLVED_EVENT_DATE,
            doorsTime = parseDoorsTime(document.textAt(".doors")),
            sourceUrl = sourceUrl,
            sourceId = "${eventSource.sourceIdPrefix}$slug",
            ticketUrl = parseTicketUrl(document, sourceUrl),
            artists = parseLineUp(document) + parseSupport(document)
        )
    }

    /**
     * The acts after a `Line Up:` label in `.event_body`, to the end of its paragraph, each a
     * headliner in page order. Empty without the label.
     *
     * The 90s Super Show puts one act per `<br>` line; Die besten Comedians puts a comma list on the
     * label's own line. One line is one act, so `Masterboy & Beatrix Delgado` stays whole. The colon
     * is required: Die Schlagernacht des Jahres links its bill as `…finden Sie hier: <a>Line Up</a>`.
     */
    private fun parseLineUp(document: Document): List<ScrapedArtist> {
        val lines =
            document
                .select(".event_body p")
                .asSequence()
                .map { it.textLines() }
                .firstNotNullOfOrNull { lines ->
                    val labelAt = lines.indexOfFirst { LINE_UP_LABEL.containsMatchIn(it) }
                    if (labelAt < 0) {
                        null
                    } else {
                        val rest = LINE_UP_LABEL.split(lines[labelAt], limit = 2).last()
                        (listOf(rest) + lines.drop(labelAt + 1)).map(String::trim).filter(String::isNotEmpty)
                    }
                }.orEmpty()
        val names = lines.singleOrNull()?.split(',') ?: lines
        return names
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it) }
    }

    /**
     * The acts of a line that opens with a support label, `Support Act: Blancmange` (event 14579), as support. Only a
     * line's head is read, so prose that mentions a support act names none.
     */
    private fun parseSupport(document: Document): List<ScrapedArtist> =
        document
            .select(".event_body p")
            .flatMap { it.textLines() }
            .mapNotNull { SUPPORT_PARAGRAPH.matchEntire(it.trim())?.groupValues?.get(1) }
            .flatMap(::splitSupportActs)
            .filterNot(::isNonArtistName)
            .map { ScrapedArtist(name = it, role = ArtistRole.SUPPORT) }

    /**
     * The external ticket-shop link (AXS for both venues) from the `#tickets` block. The music
     * hall renders a **self-link before** the shop link with identical classes, so the first
     * `a.btn-tix` would store the event's own URL as its ticket link. Only a link off the venue's
     * own host qualifies.
     */
    private fun parseTicketUrl(
        document: Document,
        sourceUrl: String
    ): String? {
        val venueHost = runCatching { URI(sourceUrl).host }.getOrNull()
        return document
            .select("#tickets a.btn-tix[href]")
            .map { it.attr("href") }
            .firstOrNull { href ->
                href.startsWith("http") && runCatching { URI(href).host }.getOrNull() != venueHost
            }
    }

    /**
     * The doors time from the `", Einlass 18:30 Uhr"` line, the only place one appears. `null`
     * when the page states none.
     */
    private fun parseDoorsTime(text: String?): LocalTime? = labelledClock(text, DOORS_LABELS)

    private companion object {
        val LINE_UP_LABEL = Regex("""\bline[ -]?up\s*:""", RegexOption.IGNORE_CASE)

        val SUPPORT_PARAGRAPH = Regex("""(?:$SUPPORT_LABELS)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
    }
}
