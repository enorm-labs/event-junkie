package de.norm.events.scraper.modus

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownBillingActs
import de.norm.events.scraper.textLines
import org.jsoup.nodes.Element

/**
 * The event type of a Modus title. The venue states no category, so a title is a concert unless
 * it names another format. Its weekly `Modus Comedy` series is COMEDY: the shared comedy pattern
 * leaves a bare "comedy" alone, because a band can be called that (#2398). A detail page whose
 * [description] says `sounds by` is a DJ night (`Off-Season`, event 14869). A bare `DJ` is no cue:
 * a band's blurb mentions one too. A `… - Aufzeichnung` is the recording of a show: COMEDY when its
 * description names comedy, as event 15566's YouTube show does, else SHOW.
 */
internal fun modusEventType(
    title: String,
    description: String? = null
): String =
    when {
        isModusComedySeries(title) -> {
            EventType.COMEDY.name
        }

        isShowRecording(title) -> {
            if (description != null && COMEDY_WORD.containsMatchIn(description)) EventType.COMEDY.name else EventType.SHOW.name
        }

        else -> {
            inferConcertVenueType(title).let { type ->
                if (type == EventType.CONCERT.name && description != null && SOUNDS_BY.containsMatchIn(description)) EventType.PARTY.name else type
            }
        }
    }

/**
 * The acts a Modus page bills. The comedy series bills its lineup in [description] only, so a
 * listing tile passes `null` and bills nobody. A numbered `… Session 004` keeps its line-up secret
 * and bills nobody (#3751), and so does a show recording, whose title names the show. Any other title is read with the `<night> mit <acts>` frame unpacked:
 * `Just Signed! | mit Yasola, Victoria West & PARA` bills the three acts, not the night.
 */
internal fun modusLineup(
    title: String,
    eventType: String,
    description: Element?
): List<ScrapedArtist> =
    when {
        isModusComedySeries(title) -> comedySeriesLineup(description)
        NUMBERED_SESSION.containsMatchIn(title) -> emptyList()
        isShowRecording(title) -> emptyList()
        else -> buildArtistsForEventType(withoutSeriesName(title, eventType), subtitle = null, eventType = eventType, unpackWithFrame = true)
    }

/**
 * A concert title without its `<series> - ` prefix when the rest is a list of acts:
 * `With a Twist - Goldie333, JnnrHndrxx & ORANYA` bills the three acts (#3751). A rest with no list
 * separator is the act's tour or record (`Yorick Thiede - Polygon`), so that title stays whole.
 * A known duo loses its tour before the split, which would otherwise cut it: `viko & penglord Aquarium on tour 2026`.
 */
private fun withoutSeriesName(
    title: String,
    eventType: String
): String {
    if (eventType != EventType.CONCERT.name) return title
    val withoutTour = title.replace(RECORD_ON_TOUR, "")
    if (knownBillingActs(withoutTour) != null) return withoutTour
    val acts = title.substringAfter(SERIES_SEPARATOR, "")
    return if (ACT_LIST_SEPARATOR.containsMatchIn(acts)) acts else title
}

/** Whether [title] is the venue's own comedy series, whose name is never an act. */
private fun isModusComedySeries(title: String): Boolean = COMEDY_SERIES.containsMatchIn(title)

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
        .mapIndexed { index, name -> ScrapedArtist(name = name, role = if (index == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT) }

private const val SERIES_SEPARATOR = " - "

/** A one-word record before `on tour`. Only a known duo may lose it, because the word can also be part of an act's name. */
private val RECORD_ON_TOUR = Regex("""\s+\S+\s+on\s+tour\b.*$""", RegexOption.IGNORE_CASE)

private val ACT_LIST_SEPARATOR = Regex(""",\s|\s&\s""")

private val NUMBERED_SESSION = Regex("""\bsession\s+\d+\s*$""", RegexOption.IGNORE_CASE)

/** `WITH SOUNDS BY`: the venue's words for the DJs of a club night. */
private val SOUNDS_BY = Regex("""\bsounds\s+by\b""", RegexOption.IGNORE_CASE)

/** Whether [title] is the recording of a show before an audience: `falsch, aber lustig - Aufzeichnung`. */
private fun isShowRecording(title: String): Boolean = SHOW_RECORDING.containsMatchIn(title)

private val SHOW_RECORDING = Regex("""\s[-–]\s*Aufzeichnung\s*$""", RegexOption.IGNORE_CASE)

private val COMEDY_WORD = Regex("""\bcomedy\b""", RegexOption.IGNORE_CASE)

private val COMEDY_SERIES = Regex("""^modus\s+comedy\b""", RegexOption.IGNORE_CASE)

/** A sentence, a `label: text` line or more than five words: comedian names are one to four words. */
private fun isProse(line: String): Boolean = line.last() in SENTENCE_END || LABEL_COLON.containsMatchIn(line) || line.split(WHITESPACE).size > MAX_NAME_WORDS

private const val SENTENCE_END = ".!?"

private const val MAX_NAME_WORDS = 5

private val LABEL_COLON = Regex("""\w: """)

private val HOST_LABEL = Regex("""^\s*host\s*:\s*""", RegexOption.IGNORE_CASE)
