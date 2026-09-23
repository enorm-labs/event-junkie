package de.norm.events.scraper.colosseum

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.time.LocalTime

/**
 * Pure parser for one Colosseum event page (`/details-registrierung/<slug>`), read for its times
 * and for nothing else.
 *
 * **The page states two Einlass/Beginn pairs, and the field they sit in tells them apart.** The
 * payload's `about` is a cloned block the house never rewrites: all 18 live events carry the same
 * `Einlass: 19 Uhr / Beginn: 20 Uhr` there, over a Dustin O'Halloran biography. The rich-content
 * `longDescription` carries the event's own lines, and all 18 have them. Reading the field rather
 * than the formatting is what gets the hard rows right: the Gene Krupa Show states `Einlass: 17
 * Uhr` without minutes, and Peter Sandberg states the boilerplate's own clocks as its own text
 * (#1684).
 *
 * **The listing's time is the Einlass more often than the Beginn** — 10 of 18 against 8 — so it
 * cannot be relabelled without this page, and the pair read here replaces it whole.
 *
 * The description and the price are still refused, for the reasons in
 * [ColosseumWebsiteImporter]'s KDoc.
 *
 * @see ColosseumWebsiteImporter for the fetch orchestration.
 */
class ColosseumDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the doors and start times of one event page, or null when the payload cannot be read.
     *
     * The returned event carries the times and the page's own title and date; everything else is
     * the listing's, which [ColosseumWebsiteImporter] merges back in.
     */
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val event = WixEventsWarmupData.event(document, EventSource.COLOSSEUM) ?: return null
        val schedule = parseWixSchedule(event.path("scheduling").path("config"))
        val lines = timeLines(event.path("longDescription"))
        val start = lines[BEGINN] ?: schedule.startTime
        // An Einlass equal to the start is the listing's own time read back, not a door time.
        val doors = lines[EINLASS]?.takeIf { it != start }
        if (lines.isEmpty()) {
            logger.debug { "Colosseum event page states no Einlass or Beginn time: $url" }
        }
        return ScrapedEvent(
            title = event.stringOrNull("title").orEmpty(),
            eventDate = schedule.date ?: UNRESOLVED_EVENT_DATE,
            doorsTime = doors,
            startTime = start,
            sourceUrl = url,
            sourceId = "${EventSource.COLOSSEUM.sourceIdPrefix}${event.stringOrNull("slug").orEmpty()}"
        )
    }

    /**
     * The clocks the event's own text states, by their label.
     *
     * A label with nothing after it yields no entry, and the listing's time stands. No live event
     * writes one today; the house edits these lines by hand, so the case is covered rather than
     * assumed away.
     */
    private fun timeLines(richContent: JsonNode): Map<String, LocalTime> =
        paragraphs(richContent)
            .mapNotNull { text -> TIME_LINE.find(text) }
            .associate { match ->
                val (label, hour, minute) = match.destructured
                label.lowercase() to LocalTime.of(hour.toInt(), minute.ifEmpty { "0" }.toInt())
            }

    /**
     * The rich content's paragraphs, each as one string.
     *
     * **A line is read per paragraph, not per text node.** Wix splits a styled line into one node
     * per run, so a bold label and its clock are siblings — `Einlass:` then ` 19:00 Uhr` — and six
     * of the eighteen events kept the listing's time because neither half matched on its own.
     */
    private fun paragraphs(node: JsonNode): List<String> =
        buildList {
            if (node.stringOrNull("type") == PARAGRAPH) add(joinedText(node))
            node.path("nodes").forEach { child -> addAll(paragraphs(child)) }
        }

    /** Every `textData.text` below [node], joined in document order. */
    private fun joinedText(node: JsonNode): String =
        buildString {
            node.path("textData").stringOrNull("text")?.let { append(it) }
            node.path("nodes").forEach { child -> append(joinedText(child)) }
        }

    private companion object {
        const val PARAGRAPH = "PARAGRAPH"
        const val EINLASS = "einlass"
        const val BEGINN = "beginn"

        /** `Einlass: 18:30 Uhr`, and `Beginn: 20 Uhr` — the house writes the minutes only sometimes. */
        val TIME_LINE = Regex("""^\s*(Einlass|Beginn)\s*:?\s*(\d{1,2})(?::(\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)
    }
}
