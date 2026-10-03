package de.norm.events.scraper.modus

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Element

/**
 * The event type of a Modus title. The venue states no category, so a title is a concert unless
 * it names another format. Its weekly `Modus Comedy` series is COMEDY: the shared comedy pattern
 * leaves a bare "comedy" alone, because a band can be called that (#2398).
 */
internal fun modusEventType(title: String): String = if (isModusComedySeries(title)) EventType.COMEDY.name else inferConcertVenueType(title)

/** Whether [title] is the venue's own comedy series, whose name is never an act. */
internal fun isModusComedySeries(title: String): Boolean = COMEDY_SERIES.containsMatchIn(title)

/**
 * The comedians a `Modus Comedy` page bills: its description is the lineup, one name per line,
 * the host first behind a `Host :` label (`Host :Till Reiners<br>Rebecca Pap<br>…`).
 */
internal fun comedySeriesLineup(description: Element?): List<ScrapedArtist> =
    description
        ?.select("p")
        .orEmpty()
        .flatMap { it.textLines() }
        .map { it.replaceFirst(HOST_LABEL, "").trim() }
        .filter { it.isNotEmpty() && !isNonArtistName(it) }
        .distinct()
        .mapIndexed { index, name -> ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT") }

private val COMEDY_SERIES = Regex("""^modus\s+comedy\b""", RegexOption.IGNORE_CASE)

private val HOST_LABEL = Regex("""^\s*host\s*:\s*""", RegexOption.IGNORE_CASE)
