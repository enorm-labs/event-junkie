package de.norm.events.scraper.modus

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.WHITESPACE
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
 * the host first behind a `Host :` label (`Host :Till Reiners<br>Rebecca Pap<br>…`). A blurb
 * follows the names, so reading stops at the first prose line; a name inside the blurb
 * ("präsentiert von Till Reiners") is never billed (#3217).
 */
internal fun comedySeriesLineup(description: Element?): List<ScrapedArtist> =
    description
        ?.select("p")
        .orEmpty()
        .flatMap { it.textLines() }
        .map { it.replaceFirst(HOST_LABEL, "").trim() }
        .filter { it.isNotEmpty() }
        .takeWhile { !isProse(it) }
        .filterNot { isNonArtistName(it) }
        .distinct()
        .mapIndexed { index, name -> ScrapedArtist(name = name, role = if (index == 0) "HEADLINER" else "SUPPORT") }

private val COMEDY_SERIES = Regex("""^modus\s+comedy\b""", RegexOption.IGNORE_CASE)

/** A sentence, a `label: text` line or more than five words: comedian names are one to four words. */
private fun isProse(line: String): Boolean = line.last() in SENTENCE_END || LABEL_COLON.containsMatchIn(line) || line.split(WHITESPACE).size > MAX_NAME_WORDS

private const val SENTENCE_END = ".!?"

private const val MAX_NAME_WORDS = 5

private val LABEL_COLON = Regex("""\w: """)

private val HOST_LABEL = Regex("""^\s*host\s*:\s*""", RegexOption.IGNORE_CASE)
