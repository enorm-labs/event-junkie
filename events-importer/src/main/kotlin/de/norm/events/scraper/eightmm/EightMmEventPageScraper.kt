package de.norm.events.scraper.eightmm

import de.norm.events.common.deshout
import de.norm.events.event.ArtistRole
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import org.jsoup.nodes.Document

/**
 * Reads an 8MM event page into its listing event: the blurb, and the `Doors: 19:00` / `Show: 20:30` lines a concert
 * blurb ends with. The calendar's own time is a slot the venue rarely edits, so a stated show time replaces it.
 * A page without a blurb leaves the event as listed; the footer's `sqs-html-content` sits outside the `article`.
 *
 * A title outside the house frame bills nobody, so a blurb's `Line up -` list bills that night instead: the
 * festival days name their acts only there, slash-separated, ending in `+ MORE` (event 12287).
 */
class EightMmEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        val description =
            htmlParagraphText(document.selectFirst("article.eventitem .eventitem-column-content .sqs-html-content")?.html())
                ?: return event
        return event.copy(
            description = description,
            doorsTime = labelledClock(description, DOORS_LABELS),
            startTime = labelledClock(description, START_LABELS) ?: event.startTime,
            artists = event.artists.ifEmpty { lineUp(description) }
        )
    }

    /** The acts of the blurb's `Line up -` list, on the label's line or the one below it; none without the label. */
    private fun lineUp(description: String): List<ScrapedArtist> {
        val lines = description.lines()
        val index = lines.indexOfFirst { LINE_UP_LABEL.containsMatchIn(it) }
        if (index < 0) return emptyList()
        val list = lines[index].replace(LINE_UP_LABEL, "").trim().ifEmpty { lines.getOrNull(index + 1).orEmpty() }
        return list
            .replace(AND_MORE, "")
            .split('/')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = deshout(it), role = ArtistRole.HEADLINER) }
    }

    private companion object {
        val LINE_UP_LABEL = Regex("""^line[\s-]?up\s*[-–:]\s*""", RegexOption.IGNORE_CASE)

        val AND_MORE = Regex("""\s*\+\s*more\s*$""", RegexOption.IGNORE_CASE)
    }
}
