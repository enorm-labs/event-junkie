package de.norm.events.scraper.silentgreen

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapEventType

// Field mapping for silent green's own vocabulary — its category labels, its "… präsentiert"
// credit line, and the host prefixes it puts in front of a billed act. Kept in one file beside
// the two scrapers because the calendar is where all of it is read.

/**
 * The event type, from the venue's own category label(s) — `Konzert`, `Ausstellung`,
 * `Filmvorführung`, `Panel, Lesung, Festival, Konzert`.
 *
 * The label is a **comma-separated list of every format an evening contains**, so a single type
 * has to be chosen out of it, and the order the venue writes them in is not that choice: the
 * Pop-Kultur Festival is tagged `Panel, Lesung, Festival, Konzert` and is a festival, and the
 * `Islands of Time` opening is tagged `Konzert, Ausstellung` and is an exhibition with live sets.
 * So the labels are ranked by [CATEGORY_PRECEDENCE] and the strongest wins — festival first, then
 * the non-musical formats, then the concert that most of these evenings also are. Beyond naming
 * the evening correctly, that ranking is what keeps an exhibition or film title out of the artist
 * table: only a `CONCERT` mints headliners from the title (see [silentGreenArtists]).
 *
 * An event with **no** category at all (the Sommerfest, the historic guided tours, the label
 * market) falls back to [inferUnmarkedTitleType]: a keyword in the title if there is an
 * unambiguous one, otherwise `OTHER`. Deliberately not the concert-venue default — this house
 * programmes exhibitions, talks and festivals as readily as gigs, so an unlabelled entry is not
 * presumed to be a concert.
 */
fun silentGreenEventType(
    categories: String?,
    title: String
): String =
    categories
        ?.split(',')
        ?.mapNotNull { mapEventType(it, SILENT_GREEN_CATEGORIES) }
        ?.minByOrNull { CATEGORY_PRECEDENCE[it] ?: CATEGORY_PRECEDENCE.size }
        ?: inferUnmarkedTitleType(title)

/**
 * The promoters of an event, from the credit line the calendar prints under its title —
 * `"silent green präsentiert"`, `"Berlin Atonal & silent green präsentieren"`,
 * `"silent green, Mansions and Millions & Puschen präsentieren"`.
 *
 * Names are split on commas and ampersands only, never on `and`/`und`: `Mansions and Millions` is
 * one label, and the shared [de.norm.events.scraper.splitSupportActs] would cut it in two. Returns
 * an empty list for any other sub-line — the venue also uses that slot for a genuine sub-title
 * (`"Zukunft. Sicher. Gestalten."`), which [silentGreenSubtitle] keeps instead.
 */
fun silentGreenPresenters(subLine: String?): List<String> =
    presenterNames(subLine)
        ?.split(PRESENTER_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()

/** The calendar's sub-line as a subtitle, or `null` when it is the [silentGreenPresenters] credit line. */
fun silentGreenSubtitle(subLine: String?): String? = subLine?.takeIf { presenterNames(it) == null }

/**
 * The billed acts of an event, as [ScrapedArtist] entries placed in the [hall] the evening
 * runs in.
 *
 * Delegates to the shared [buildArtistsForEventType], so only a `CONCERT` turns its title into
 * headliners — an exhibition, a talk, a screening or a festival mints nothing, which is what the
 * [silentGreenEventType] ranking exists to guarantee. The title is passed through
 * [stripHostPrefix] first so the label or series hosting the night does not become part of the
 * act's name.
 *
 * The [hall] is carried on the lineup entries because `stage` is the only place the model has for
 * a room, the same way the multi-floor clubs use it. An evening with no lineup — every exhibition
 * and talk here — therefore records no hall at all; the venue publishes one for those too, but
 * there is no event-level field to put it in.
 */
fun silentGreenArtists(
    title: String,
    eventType: String,
    hall: String?
): List<ScrapedArtist> =
    buildArtistsForEventType(stripHostPrefix(title), subtitle = null, eventType = eventType)
        .map { it.copy(stage = hall) }

/**
 * Strips the label or series hosting a night from the front of its title, so what remains is the
 * act(s) billed after it.
 *
 * Two forms, and only the first is unconditional. `"<host> pres. <acts>"` says in words that the
 * name in front is the host and the ones after it are the programme — `"Unguarded pres.
 * Jungstötter + Blurrydog"`, `"hub pres. Doorman + Franco Franco"` — so the prefix always goes.
 * The trade is `"Burnt Friedman pres. Secret Rhythms"`, where the host *is* an artist and only
 * his project name survives; that is the rarer case and still yields a real act.
 *
 * A bare `"<series>: <acts>"` colon is far weaker — `"The I in the Mirror: Reflection"` is a
 * title, not a series and a support act — so it is stripped **only when the remainder still bills
 * more than one act**. A title that lists several acts after a colon has named the series in front
 * of it (`"Psychic Liberation Night: Niloofar Asghary + Júlia Koffler"`, `"15 YEARS
 * zweikommasieben: Anna Homler + Steven Warwick + zweikommasieben DJs"`); a title with a single
 * name after the colon has not.
 *
 * Only the derived artist names are affected — the stored event title keeps the venue's billing
 * verbatim.
 */
private fun stripHostPrefix(title: String): String {
    val withoutHost =
        title
            .trim()
            .replaceFirst(PRESENTED_BY_PREFIX, "")
            .trim()
            .ifBlank { title.trim() }
    val withoutSeries = withoutHost.replaceFirst(SERIES_PREFIX, "").trim()
    return if (withoutSeries != withoutHost && CO_BILL_SEPARATOR.containsMatchIn(withoutSeries)) withoutSeries else withoutHost
}

/** The credit line's names, or `null` when [subLine] is not a "… präsentiert/präsentieren" line. */
private fun presenterNames(subLine: String?): String? =
    subLine
        ?.trim()
        ?.let { PRESENTER_LINE.find(it) }
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.isNotBlank() }

/**
 * Category labels the shared table does not carry, for the formats this house programmes beside
 * concerts. `Konferenz` has no nearer type than `OTHER`; the spoken-word formats it invents
 * freely (`Panel`, `Vortrag`, `Artist-Talk`, `Buchpremiere`) map to `READING`, the model's
 * spoken-word bucket, for the same reason the Urania's do.
 */
private val SILENT_GREEN_CATEGORIES =
    mapOf(
        "installation" to EventType.EXHIBITION.name,
        "filmvorführung" to EventType.SCREENING.name,
        "artist-talk" to EventType.READING.name,
        "panel" to EventType.READING.name,
        "vortrag" to EventType.READING.name,
        "buchpremiere" to EventType.READING.name,
        "performance" to EventType.SHOW.name,
        "konferenz" to EventType.OTHER.name
    )

/**
 * How strongly a category label claims the evening, strongest first, when the venue tags one with
 * several. A festival subsumes everything inside it; the non-musical formats come next, because
 * they are what a title alone would not reveal and what must not be read as a lineup; `CONCERT`
 * ranks last of the real formats because nearly every evening here contains music. Anything
 * unranked (the shared table's `PARTY`, `QUIZ`, …) sorts after all of them.
 */
private val CATEGORY_PRECEDENCE: Map<String, Int> =
    listOf(
        EventType.FESTIVAL,
        EventType.EXHIBITION,
        EventType.SCREENING,
        EventType.READING,
        EventType.SHOW,
        EventType.CONCERT,
        EventType.OTHER
    ).withIndex().associate { (index, type) -> type.name to index }

/** The venue's credit line, capturing the presenters in front of its "präsentiert"/"präsentieren" verb. */
private val PRESENTER_LINE = Regex("""^(.+?)\s+präsentier(?:t|en)$""", RegexOption.IGNORE_CASE)

/** Separates co-presenters. Comma and ampersand only — see [silentGreenPresenters]. */
private val PRESENTER_SEPARATOR = Regex("""\s*[,&]\s*""")

/** A `"<host> pres./presents/präsentiert "` lead-in, up to a name's worth of text long. */
private val PRESENTED_BY_PREFIX =
    Regex("""^.{2,60}?\s+(?:pres\.|presents|präsentiert)\s+""", RegexOption.IGNORE_CASE)

/** A `"<series>: "` lead-in — no colon or `+` inside it, so only the outermost one is taken. */
private val SERIES_PREFIX = Regex("""^[^:+]{2,60}:\s+""")

/** The space-padded `+` with which this venue separates co-billed acts. */
private val CO_BILL_SEPARATOR = Regex("""\s\+\s""")
