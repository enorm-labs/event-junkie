package de.norm.events.scraper.barjedervernunft

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.performersListed
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * Pure HTML parser for a Bar jeder Vernunft **show page** (`/de/programm/programmuebersicht/<show>.html`).
 *
 * A show page describes a production, not a night: the calendar links every date of a run to
 * the same page. It carries the three fields the calendar card omits — `Genre`, the `Preise`
 * range, and the untruncated blurb — which [BarJederVernunftWebsiteImporter] applies to every
 * date of that show.
 *
 * The `Überblick` block is a label/value grid (`Spielzeit`, `Programmhinweis`, `Genre`,
 * `Preise`) whose column count varies per show (`col-lg-3` / `col-lg-4`), so values are looked
 * up by **label text**, not position.
 *
 * @see BarJederVernunftWebsiteImporter for the HTTP fetch orchestrator.
 */
class BarJederVernunftShowPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the show-level fields, or `null` when the page has none (e.g. a redirect or error
     * page served with a 200).
     */
    fun scrape(document: Document): BarJederVernunftShow? {
        val genre = overviewValue(document, "Genre")?.text().blankToNull()
        // The Preise block stacks regular / reduced / special-performance prices as <br>-separated
        // lines in one <p>; only the first is the regular admission range.
        val priceLine = overviewValue(document, "Preise")?.textLinesAt("p")?.firstOrNull()
        val description = description(document)

        if (genre == null && priceLine == null && description == null) {
            logger.warn { "Show page carries no genre, price or description block" }
            return null
        }

        return BarJederVernunftShow(
            genre = genre,
            // "Ab 19,90 € bis 49,90 €" — the lowest price of the range is what a ticket starts at.
            pricePresale = parsePriceValue(priceLine),
            priceNote = priceLine,
            description = description
        )
    }

    /**
     * The value cell of the `Überblick` row labelled [label], or `null` without such a row
     * (`Programmhinweis` is optional, and a one-night show may drop `Spielzeit`).
     */
    private fun overviewValue(
        document: Document,
        label: String
    ): Element? =
        document
            .select(".event-overview > div")
            .firstOrNull {
                it
                    .selectFirst(".h6")
                    ?.text()
                    ?.trim()
                    .equals(label, ignoreCase = true)
            }?.selectFirst(".h6 + div")

    /**
     * The `Überblick` prose paragraphs joined into the show description. Scoped to `#ueberblick`
     * so the later `Mitwirkende` (cast and creative team) and ticketing sections stay out, and to
     * `.vivomedia-text` so the pull-quote card beside the blurb is not folded in. The English page
     * names the section `#at-a-glance`.
     */
    fun description(document: Document): String? =
        document
            .select("#ueberblick, #at-a-glance")
            .select(".indent-contentcollection .vivomedia-text p")
            .paragraphText()
}

/**
 * The show-level fields shared by every date of one production. Applied to each calendar
 * occurrence by [applyTo], where the venue's own `Genre` also decides the event type — and so
 * whether the billed name is an artist at all.
 */
data class BarJederVernunftShow(
    /** The venue's own genre label, e.g. "Chanson", "Musik-Show". */
    val genre: String?,
    /** Lowest price of the regular admission range. */
    val pricePresale: BigDecimal?,
    /** The regular admission range verbatim, e.g. "Ab 19,90 € bis 49,90 €". */
    val priceNote: String?,
    /** The untruncated show blurb (the calendar's JSON-LD carries a cut-off teaser). */
    val description: String?
) {
    /**
     * Returns [event] enriched with this show's genre, prices, description and derived event type.
     *
     * The type is resolved here, not in the calendar scraper, because the genre — the only signal
     * this venue gives — lives on the show page. It also decides the lineup:
     * [buildArtistsForEventType] mints the billed name as headliner for a `CONCERT` and nothing for
     * a `SHOW`. Then the title is read as an act ([performerOfShow]), and last the subtitle as a cast
     * ([castOfSubtitle]). "Tim Fischer" (Chanson) is a performer; "Oh What A Night!" (Musik-Show) is a
     * production and must not become an artist row.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val eventType = resolveEventType(genre)
        val blurb = description ?: event.description
        return event.copy(
            eventType = eventType,
            genre = genre ?: event.genre,
            pricePresale = pricePresale ?: event.pricePresale,
            priceNote = priceNote ?: event.priceNote,
            description = blurb,
            artists =
                buildArtistsForEventType(event.title, event.subtitle, eventType)
                    .ifEmpty { performerOfShow(event.title, eventType, genre, blurb) }
                    .ifEmpty { castOfSubtitle(event.subtitle, eventType, blurb) }
        )
    }
}

/**
 * The act a `SHOW`, `COMEDY` or `READING` title names. An unnamed band comes off, `Gitte Haenning & Band` bills
 * `Gitte Haenning`, and a band in the act's own name stays: `Gustav Peter Wöhler Band`.
 *
 * The genre decides what vouches for the name. The programme is the subtitle, so a comedy title shaped like a name is
 * the comedian: `Bodo Wartke` (event 12189). A `Musik-Show` or a reading needs the blurb to name the act by one of its
 * names, so `Gitte` vouches for `Gitte Haenning`. Any other `SHOW` needs the blurb to open with the title, `Rainald
 * Grebe ist Autor, …` (event 11998), because a production carries a name too. One word needs the blurb to name it:
 * `Rock4` and `Ass-Dur`, not `Silvestergala`. `Oh What A Night!`, `SAGO Song Salon` and `CABARET` bill nobody.
 */
private fun performerOfShow(
    title: String,
    eventType: String,
    genre: String?,
    description: String?
): List<ScrapedArtist> {
    val act = title.replace(UNNAMED_BAND, "")
    val musicShow = genre.equals(MUSIC_SHOW_GENRE, ignoreCase = true)
    val vouched =
        when {
            eventType == EventType.COMEDY.name -> true
            eventType == EventType.READING.name || (eventType == EventType.SHOW.name && musicShow) -> namedInBlurb(act, description)
            eventType == EventType.SHOW.name -> description?.startsWith("$title ") == true || description?.startsWith("$title,") == true
            else -> false
        }
    val actShaped = PERFORMER_NAME.matches(act) || PERFORMER_DUO.matches(act) || NAMED_BAND.matches(act)
    val oneWordAct = ONE_WORD_NAME.matches(act) && namedInBlurb(act, description)
    if (!vouched || !(actShaped || oneWordAct)) return emptyList()
    return listOf(ScrapedArtist(name = act, role = ArtistRole.HEADLINER, titleDerived = true))
}

/**
 * The cast a subtitle names when the title names none: `mit Sven Ratzke & Band` (Silvestergala), `Katharina Thalbach
 * liest „…“` (event 8744), or a bare list of two or more names the blurb names too, `Martin Reinl & Carsten Haffke`
 * (PUPPEN GUCKEN). A bare single name is refused, because `Die Neujahrsshow` is shaped like one.
 */
private fun castOfSubtitle(
    subtitle: String?,
    eventType: String,
    description: String?
): List<ScrapedArtist> {
    if (subtitle == null || eventType !in CAST_EVENT_TYPES) return emptyList()
    val line = subtitle.trim().replace(UNNAMED_BAND, "")
    SUBTITLE_BILLED_WITH.find(line)?.let { return performersListed(it.groupValues[1]) }
    if (eventType == EventType.READING.name) READER.find(line)?.let { return performersListed(it.groupValues[1]) }
    return performersListed(line).takeIf { cast -> cast.size > 1 && cast.all { namedInBlurb(it.name, description) } }.orEmpty()
}

/** Whether [description] names [act] by one of its names, as a whole word. Articles and particles are no evidence. */
private fun namedInBlurb(
    act: String,
    description: String?
): Boolean {
    if (description == null) return false
    return act
        .split(NAME_PARTS)
        .filter { it.length >= MIN_EVIDENCE_LENGTH && it.lowercase() !in NAME_PARTICLES }
        .any { Regex("""(?<![\p{L}\p{N}-])${Regex.escape(it)}(?![\p{L}\p{N}-])""").containsMatchIn(description) }
}

private const val MUSIC_SHOW_GENRE = "musik-show"

private val CAST_EVENT_TYPES = setOf(EventType.SHOW.name, EventType.COMEDY.name, EventType.READING.name)

/** A band, guests or an orchestra the title or subtitle does not name: `& Band`, `mit Band`, `& Gäste`. */
private val UNNAMED_BAND = Regex("""\s+(?:&|und|mit)\s+(?:Band|Gäste|Friends|Ensemble|Orchester)$""")

private val SUBTITLE_BILLED_WITH = Regex("""^[Mm]it\s+(.+)$""")

private val READER = Regex("""^(.+?)\s+(?:liest|lesen)\s""")

private val NAME_PARTS = Regex("""[\s&]+""")

/** `Pe Werner` is evidenced by `Werner`, never by `Pe`. */
private const val MIN_EVIDENCE_LENGTH = 3

private val NAME_PARTICLES = setOf("the", "die", "der", "das", "von", "van", "und")

private const val NAME_WORD = """\p{Lu}\p{Ll}[\p{L}'-]*"""

/**
 * Two or three capitalised words, none of them shouted, with a name particle allowed between them:
 * `Katharine Mehrling`, `Eckart von Hirschhausen`, not `SAGO Song Salon`.
 */
private val PERFORMER_NAME = Regex("""^$NAME_WORD(?:\s+(?:von\s+|van\s+|zu\s+)?$NAME_WORD){1,2}$""")

/** Two performers billed as one act, one to three words a side: `Siegfried & Joy` (event 13924), `Christoph Maria Herbst & Moritz Netenjakob`. */
private const val DUO_NAME = """$NAME_WORD(?:\s+$NAME_WORD){0,2}"""

private val PERFORMER_DUO = Regex("""^$DUO_NAME\s+&\s+$DUO_NAME$""")

/** A band named for its leader, `Gustav Peter Wöhler Band`. */
private val NAMED_BAND = Regex("""^$NAME_WORD(?:\s+$NAME_WORD){0,2}\s+Band$""")

/** One capitalised word, digits allowed after the first two letters: `Rock4`, `Ass-Dur`. */
private val ONE_WORD_NAME = Regex("""^\p{Lu}\p{Ll}[\p{L}\p{N}'-]*$""")

/**
 * Maps a `Genre` label to an [EventType] name.
 *
 * Everything here is an evening in the Spiegelzelt or the TIPI tent, so the genre separates the cases: a
 * **music style** ([MUSIC_GENRES]) is a concert whose billed name is the performer, a **comedy
 * format** ([COMEDY_GENRES]) a comedy night, a **staged format** ([STAGE_FORMAT_GENRES]) a show
 * whose billed name is the production, a **spoken format** ([SPOKEN_GENRES]) a reading. Unrecognized or
 * missing defaults to [EventType.SHOW] — the safe side, minting no artist. The lists are
 * curated from the venue's programme; add a new music style when one appears, otherwise a
 * genuine concert is filed as a show and its performer is lost.
 */
internal fun resolveEventType(genre: String?): String = mapEventType(genre, BAR_JEDER_VERNUNFT_GENRES) ?: EventType.SHOW.name

/** Music styles the venue programmes — the billed name is the act. */
private val MUSIC_GENRES = listOf("chanson", "a cappella", "swing", "singer/songwriter")

/** Staged formats the venue programmes — the billed name is the production, not a performer. */
private val STAGE_FORMAT_GENRES = listOf("musik-show", "musical", "show")

/** Comedy formats, Kabarett among them (#2314). */
private val COMEDY_GENRES = listOf("musik-comedy", "musik-kabarett", "kabarett", "kabarett-theater", "puppen-comedy", "satire")

/** Spoken formats: a reading, or a talk on stage, as Peter Edel files its `Klubgespräch`. */
private val SPOKEN_GENRES = listOf("lesung & gespräch", "talk")

/** Venue-specific genre synonyms handed to [mapEventType]; see [resolveEventType]. */
private val BAR_JEDER_VERNUNFT_GENRES: Map<String, String> =
    MUSIC_GENRES.associateWith { EventType.CONCERT.name } +
        STAGE_FORMAT_GENRES.associateWith { EventType.SHOW.name } +
        COMEDY_GENRES.associateWith { EventType.COMEDY.name } +
        SPOKEN_GENRES.associateWith { EventType.READING.name }
