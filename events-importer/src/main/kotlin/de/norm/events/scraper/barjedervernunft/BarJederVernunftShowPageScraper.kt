package de.norm.events.scraper.barjedervernunft

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.math.BigDecimal

/**
 * Pure HTML parser for a Bar jeder Vernunft **show page**
 * (`/de/programm/programmuebersicht/<show>.html`).
 *
 * A show page describes a production, not a single night: the calendar links every date
 * of a run to the same page. It carries the three fields the calendar card omits — the
 * `Genre`, the `Preise` range, and the untruncated blurb — which
 * [BarJederVernunftWebsiteImporter] applies to every date of that show.
 *
 * The page's own `Überblick` block is a label/value grid (`Spielzeit`, `Programmhinweis`,
 * `Genre`, `Preise`), whose column count varies per show (`col-lg-3` / `col-lg-4`), so
 * values are looked up by their **label text** rather than by position.
 *
 * @see BarJederVernunftWebsiteImporter for the HTTP fetch orchestrator.
 */
class BarJederVernunftShowPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the show-level fields from a show page, or `null` when the page carries
     * none of them (e.g. a redirect or an error page served with a 200).
     */
    fun scrape(document: Document): BarJederVernunftShow? {
        val genre = overviewValue(document, "Genre")?.text()?.trim()?.takeIf { it.isNotBlank() }
        // The Preise block stacks regular / reduced / special-performance prices as
        // <br>-separated lines in one <p>; only the first is the regular admission range.
        val priceLine = overviewValue(document, "Preise")?.textLinesAt("p")?.firstOrNull()
        val description = parseDescription(document)

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
     * Reads the value cell of the `Überblick` grid row whose label is [label], or `null`
     * when the show page has no such row (`Programmhinweis` is optional, and a one-night
     * show may drop `Spielzeit`).
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
     * Joins the prose paragraphs of the `Überblick` section into the show description.
     *
     * Scoped to `#ueberblick` so the later `Mitwirkende` (cast and creative team) and
     * ticketing sections stay out, and to `.vivomedia-text` so the pull-quote card
     * rendered alongside the blurb is not folded into it.
     */
    private fun parseDescription(document: Document): String? =
        document
            .select("#ueberblick .indent-contentcollection .vivomedia-text p")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }
}

/**
 * The show-level fields shared by every date of one Bar jeder Vernunft production.
 *
 * Applied to each calendar occurrence by [applyTo], which is where the venue's own
 * `Genre` also decides the event type — and therefore whether the billed name is an
 * artist at all.
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
     * Returns [event] enriched with this show's genre, prices, description and derived
     * event type.
     *
     * The type is resolved here rather than in the calendar scraper because the genre —
     * the only signal this venue gives — lives on the show page. It also decides the
     * lineup: [buildArtistsForEventType] mints the billed name as headliner for a
     * `CONCERT` and nothing for a `SHOW`, which is the distinction that matters here.
     * "Tim Fischer" (Chanson) is a performer; "Oh What A Night!" (Musik-Show) is the
     * name of a production and must not become an artist row.
     */
    fun applyTo(event: ScrapedEvent): ScrapedEvent {
        val eventType = resolveEventType(genre)
        return event.copy(
            eventType = eventType,
            genre = genre ?: event.genre,
            pricePresale = pricePresale ?: event.pricePresale,
            priceNote = priceNote ?: event.priceNote,
            description = description ?: event.description,
            artists = buildArtistsForEventType(event.title, event.subtitle, eventType)
        )
    }
}

/**
 * Maps a Bar jeder Vernunft `Genre` label to an [EventType] name.
 *
 * Everything the venue stages is an evening in its Spiegelzelt, so the genre only has to
 * separate two cases: a **music style** ([MUSIC_GENRES]) is a concert whose billed name
 * is the performer, and a **staged format** ([STAGE_FORMAT_GENRES]) is a show whose
 * billed name is the production. An unrecognized or missing genre therefore defaults to
 * [EventType.SHOW] — the safe side, since it mints no artist. The lists are curated from
 * the venue's own programme; add a new music style here when one appears, otherwise a
 * genuine concert is filed as a show and its performer is lost.
 */
private fun resolveEventType(genre: String?): String = mapEventType(genre, BAR_JEDER_VERNUNFT_GENRES) ?: EventType.SHOW.name

/** Music styles the venue programmes — the billed name is the act. */
private val MUSIC_GENRES = listOf("chanson", "a cappella", "swing", "singer/songwriter")

/** Staged formats the venue programmes — the billed name is the production, not a performer. */
private val STAGE_FORMAT_GENRES = listOf("musik-show", "musik-kabarett", "musik-comedy", "kabarett", "musical", "show")

/** Venue-specific genre synonyms handed to [mapEventType]; see [resolveEventType]. */
private val BAR_JEDER_VERNUNFT_GENRES: Map<String, String> =
    MUSIC_GENRES.associateWith { EventType.CONCERT.name } +
        STAGE_FORMAT_GENRES.associateWith { EventType.SHOW.name }
