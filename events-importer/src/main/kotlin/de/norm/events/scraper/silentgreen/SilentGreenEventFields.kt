package de.norm.events.scraper.silentgreen

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.mapEventType

// Field mapping for silent green's vocabulary: category labels, the "… präsentiert" credit
// line, and the host prefixes in front of a billed act.

/**
 * The event type from the venue's category label(s): `Konzert`, `Ausstellung`, `Filmvorführung`,
 * `Panel, Lesung, Festival, Konzert`. The label is a comma-separated list of every format an
 * evening contains, and the venue's order is not the choice: the Pop-Kultur Festival is tagged
 * `Panel, Lesung, Festival, Konzert` and is a festival, the `Islands of Time` opening `Konzert,
 * Ausstellung` and is an exhibition with live sets. So the labels are ranked by
 * [CATEGORY_PRECEDENCE], which also keeps an exhibition or film title out of the artist table:
 * only a `CONCERT` mints headliners ([silentGreenArtists]). No category at all (the Sommerfest,
 * the guided tours, the label market) falls back to [inferUnmarkedTitleType], not the
 * concert-venue default: this house programmes exhibitions, talks and festivals as readily as
 * gigs.
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
 * The promoters from the credit line under the title (`"silent green präsentiert"`, `"Berlin
 * Atonal & silent green präsentieren"`, `"silent green, Mansions and Millions & Puschen
 * präsentieren"`). Split on commas and ampersands only, never `and`/`und`: `Mansions and
 * Millions` is one label, and [de.norm.events.scraper.splitSupportActs] would cut it. Empty for
 * any other sub-line, which may be a genuine sub-title (`"Zukunft. Sicher. Gestalten."`) that
 * [silentGreenSubtitle] keeps.
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
 * The billed acts as [ScrapedArtist] entries in the [hall] the evening runs in, via
 * [buildArtistsForEventType], so only a `CONCERT` turns its title into headliners; the title
 * passes through [stripHostPrefix] first. The [hall] rides on the lineup entries because `stage`
 * is the model's only room field, as the multi-floor clubs use it; an evening with no lineup
 * records no hall.
 */
fun silentGreenArtists(
    title: String,
    eventType: String,
    hall: String?
): List<ScrapedArtist> =
    buildArtistsForEventType(stripHostPrefix(title), subtitle = null, eventType = eventType)
        .map { it.copy(stage = hall) }

/**
 * Strips the label or series hosting a night from the front of its title. A spelled-out
 * `"<host> presents <acts>"` and the venue's own `"silent green pres. <programme>"` always lose
 * the host; an abbreviated `"<host> pres. <acts>"` by anyone else is left to
 * `headlinersFromTitle`, which reads the right side: `"hub pres. Doorman + Franco Franco"` is a
 * host and its programme, `"Burnt Friedman pres. Secret Rhythms"` an act and its project (#1581).
 * A bare `"<series>: <acts>"` colon is far weaker (`"The I in the Mirror: Reflection"` is a
 * title), so it is stripped only when the remainder still bills more than one act (`"Psychic
 * Liberation Night: Niloofar Asghary + Júlia Koffler"`, `"15 YEARS zweikommasieben: Anna Homler
 * + Steven Warwick + zweikommasieben DJs"`). Only the derived artist names are affected.
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
 * Category labels the shared table lacks: `Konferenz` has no nearer type than `OTHER`; the
 * spoken-word formats (`Panel`, `Vortrag`, `Artist-Talk`, `Buchpremiere`) map to `READING`, as
 * the Urania's do.
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
 * How strongly a label claims the evening when several are tagged: a festival subsumes
 * everything; the non-musical formats next, since they must not be read as a lineup; `CONCERT`
 * last of the real formats, because nearly every evening here contains music; unranked labels
 * after all.
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

/** A `"<host> presents/präsentiert "` lead-in, or the venue presenting its own programme. */
private val PRESENTED_BY_PREFIX =
    Regex("""^(?:silent\s+green\s+pres\.|.{2,60}?\s+(?:presents|präsentiert))\s+""", RegexOption.IGNORE_CASE)

/** A `"<series>: "` lead-in — no colon or `+` inside it, so only the outermost one is taken. */
private val SERIES_PREFIX = Regex("""^[^:+]{2,60}:\s+""")

/** The space-padded `+` with which this venue separates co-billed acts. */
private val CO_BILL_SEPARATOR = Regex("""\s\+\s""")
