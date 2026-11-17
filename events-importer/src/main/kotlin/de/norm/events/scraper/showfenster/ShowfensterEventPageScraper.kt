package de.norm.events.scraper.showfenster

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.wixParagraphs
import org.jsoup.nodes.Document

/**
 * Parser for one Showfenster concert page (`/event-details/<slug>`), read for the players its full text names.
 *
 * A concert whose title is a work's name lists its players under `Es spielen:`, one `Name: instrument` per paragraph
 * (`A Life In Colour - Konzert`, #5238). Those names replace the act the calendar's title gave. A page without the line
 * keeps the title's act.
 */
class ShowfensterEventPageScraper {
    fun enrich(
        event: ScrapedEvent,
        document: Document
    ): ScrapedEvent? {
        val page = WixEventsWarmupData.event(document, EventSource.SHOWFENSTER) ?: return null
        val players = playersOf(wixParagraphs(page.path("longDescription")))
        return if (players.isEmpty()) event else event.copy(artists = players.map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) })
    }
}

/** The names on the `Name: instrument` lines after `Es spielen:`, up to the first line of another shape. */
internal fun playersOf(paragraphs: List<String>): List<String> {
    val lines = paragraphs.map { it.trim() }.filter { it.isNotEmpty() }
    val heading = lines.indexOfFirst { PLAYERS_HEADING.matches(it) }
    if (heading < 0) return emptyList()
    return lines
        .drop(heading + 1)
        .asSequence()
        .map { PLAYER_LINE.matchEntire(it)?.groupValues?.get(1) }
        .takeWhile { it != null }
        .filterNotNull()
        .toList()
}

private val PLAYERS_HEADING = Regex("""es\s+spielen\s*:?""", RegexOption.IGNORE_CASE)

/** `Jonathan Bratoëff: Konzertgitarre`, `Richard Maegraith : Flöte`: one to four words of a name, then the instrument. */
private val PLAYER_LINE = Regex("""(\p{Lu}[\p{L}'’.-]*(?:\s+[\p{L}'’.-]+){0,3})\s*:\s*\p{L}.*""")
