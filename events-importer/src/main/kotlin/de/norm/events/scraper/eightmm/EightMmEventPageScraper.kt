package de.norm.events.scraper.eightmm

import de.norm.events.common.deshout
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.stripLineupBracket
import de.norm.events.scraper.stripTitleStatusMarker
import org.jsoup.nodes.Document

/**
 * Reads an 8MM event page into its listing event: the blurb, and the `Doors: 19:00` / `Show: 20:30` lines a concert
 * blurb ends with. The calendar's own time is a slot the venue rarely edits, so a stated show time replaces it.
 * A page without a blurb leaves the event as listed; the footer's `sqs-html-content` sits outside the `article`.
 *
 * A title outside the house frame bills nobody, so a blurb's `Line up -` list bills that night instead: the
 * festival days name their acts only there, slash-separated, ending in `+ MORE` (event 12287).
 *
 * With no such list either, a concert bills the act its title names beside `LIVE` (#5469): `<act> Live plus …`,
 * `… with <act> LIVE + …` or `… LIVE - <act>`. These shapes rank last because a list names the whole bill.
 */
class EightMmEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent {
        val description =
            htmlParagraphText(document.selectFirst("article.eventitem .eventitem-column-content .sqs-html-content")?.html())
                ?: return event.copy(artists = event.artists.ifEmpty { liveTitleActs(event) })
        return event.copy(
            description = description,
            doorsTime = labelledClock(description, DOORS_LABELS),
            startTime = labelledClock(description, START_LABELS) ?: event.startTime,
            artists = event.artists.ifEmpty { lineUp(description) }.ifEmpty { liveTitleActs(event) }
        )
    }

    /** The acts of the blurb's `Line up -` list, on the label's line or the next one with text; none without the label. */
    private fun lineUp(description: String): List<ScrapedArtist> {
        val lines = description.lines()
        val index = lines.indexOfFirst { LINE_UP_LABEL.containsMatchIn(it) }
        if (index < 0) return emptyList()
        val list = lines[index].replace(LINE_UP_LABEL, "").trim().ifEmpty { lines.drop(index + 1).firstOrNull { it.isNotBlank() }.orEmpty() }
        return list
            .replace(AND_MORE, "")
            .split('/')
            .map { it.trim() }
            .map(::stripLineupBracket)
            .filter { it.isNotEmpty() && !isNonArtistName(it) }
            .map { ScrapedArtist(name = deshout(it), role = ArtistRole.HEADLINER) }
    }

    /** The act of a [LIVE_SHAPES] title; a name that holds `8MM` is the house's own, as in `8MM All star Djs`. */
    private fun liveTitleActs(event: ScrapedEvent): List<ScrapedArtist> {
        if (event.eventType != EventType.CONCERT.name) return emptyList()
        val title = stripTitleStatusMarker(event.title)
        return LIVE_SHAPES
            .firstNotNullOfOrNull { it.find(title) }
            ?.groups
            ?.get("act")
            ?.let { headlinersFromTitle(it.value) }
            .orEmpty()
            .filterNot { HOUSE.containsMatchIn(it.name) }
    }

    private companion object {
        val LINE_UP_LABEL = Regex("""^line[\s-]?up\s*[-–:]\s*""", RegexOption.IGNORE_CASE)

        /** A `with`, a colon or a dash in front of `Live plus` marks a night's frame, which is not part of a name. */
        val LIVE_SHAPES =
            listOf(
                Regex("""^(?<act>(?:(?!\swith\s|:|\s[-–—]\s).)+?)\s+LIVE\s+plus\b""", RegexOption.IGNORE_CASE),
                Regex("""\swith\s+(?<act>.+?)\s+LIVE\s*\+""", RegexOption.IGNORE_CASE),
                Regex("""^.+\sLIVE\s+[-–—]\s+(?<act>.+)$""", RegexOption.IGNORE_CASE)
            )

        val HOUSE = Regex("""\b8MM\b""", RegexOption.IGNORE_CASE)

        val AND_MORE = Regex("""\s*\+\s*more\s*$""", RegexOption.IGNORE_CASE)
    }
}
