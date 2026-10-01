package de.norm.events.scraper.berghain

import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.isNonArtistName

/**
 * Maps a Berghain floor label to its signature music genre.
 *
 * The site exposes **no** structured genre field — genre words appear only in the editorial
 * prose, attached to an individual artist rather than the night (one act "modernen Techno",
 * the next an "Acid-House-Jam"), so nothing can be reliably *extracted*. Each room does have a
 * settled programming identity, which makes the floor a useful genre **default** for filtering:
 * - **Berghain** (the main hall) → Techno
 * - **Panorama Bar** → House
 * - **Säule** (the small experimental room) → Experimental
 *
 * A curated stereotype, not ground truth: a line-up can diverge from its room's usual sound
 * (an Acid House bill in the Berghain hall does happen). Floors without one settled genre — the
 * Halle event hall and the Kantine am Berghain concert hall, both varied — yield `null`.
 *
 * The `Kantine` check comes first because "Kantine am Berghain" contains "Berghain" and must
 * not be mis-mapped to Techno.
 */
private fun floorToGenre(floor: String): String? {
    val label = floor.trim()
    return when {
        label.contains("Kantine", ignoreCase = true) -> null
        label.contains("Panorama Bar", ignoreCase = true) -> "House"
        label.contains("Säule", ignoreCase = true) -> "Experimental"
        label.contains("Berghain", ignoreCase = true) -> "Techno"
        else -> null
    }
}

/**
 * An event's genre from the floor(s) it runs on, or `null` when none maps to a settled genre.
 * Distinct floor genres are joined in listing order, so a night across the main hall and
 * Panorama Bar reads "Techno, House".
 *
 * @see floorToGenre for the per-floor mapping and its stereotype caveat.
 */
internal fun floorsToGenre(floors: List<String>): String? =
    floors
        .mapNotNull(::floorToGenre)
        .distinct()
        .joinToString(", ")
        .takeIf { it.isNotBlank() }

/**
 * Types an event from its floor label(s): the Kantine am Berghain concert hall lists live
 * [CONCERT][EventType.CONCERT]s; the Berghain building floors (Berghain, Panorama Bar, Säule,
 * Halle) host club [PARTY][EventType.PARTY] nights. `null` without a floor, letting the
 * persistence boundary apply the `OTHER` default.
 */
internal fun floorsToEventType(floors: List<String>): String? =
    when {
        floors.any { it.contains(KANTINE_MARKER, ignoreCase = true) } -> EventType.CONCERT.name
        floors.isNotEmpty() -> EventType.PARTY.name
        else -> null
    }

/**
 * Whether a Halle row is an exhibition: the event page's text calls it an `Ausstellung` or an
 * `exhibition` (#2265). The listing gives no other tell, since it bills the show's title in the
 * lineup slot of a club night.
 */
internal fun isHalleExhibition(
    floors: List<String>,
    description: String?
): Boolean = floors.any { it.contains(HALLE_FLOOR, ignoreCase = true) } && description != null && EXHIBITION_WORD.containsMatchIn(description)

private const val HALLE_FLOOR = "Halle"

private val EXHIBITION_WORD = Regex("""\b(?:ausstellung|exhibition)\b""", RegexOption.IGNORE_CASE)

/** Floor label identifying the adjacent concert hall (vs. the Berghain building's club floors). */
private const val KANTINE_MARKER = "Kantine"

/**
 * The performers one lineup slot names: `Agata B2B Cunt Remember` is two acts, `Booty Carrell DJ
 * (Vinyl) Warm-up` is one. Shared by the programme and the event page, so a running-order slot
 * names the acts the programme billed. Why each split is safe: [BerghainOverviewPageScraper].
 */
internal fun splitSlot(text: String): List<String> =
    text
        .split(SLOT_SEPARATOR)
        .map { it.replace(SLOT_DESCRIPTION, "").trim() }
        .filter { it.isNotBlank() && !isNonArtistName(it) }

/**
 * What Kantine writes after a name to describe the slot, not the act: `Booty Carrell DJ (Vinyl)
 * Warm-up`, whose `+ Party` the [SLOT_SEPARATOR] already cut off (#1843). Each part is optional,
 * and only a whole tail comes off, so `DJ Koze` keeps its leading `DJ`.
 */
private val SLOT_DESCRIPTION =
    Regex("""(?:\s+dj)?(?:\s*\((?:all\s+)?vinyl(?:\s+only)?\))?(?:\s+warm[\s-]?up)?\s*$""", RegexOption.IGNORE_CASE)

/**
 * What separates the performers written into one slot: a [B2B_SEPARATOR], a padded `+` (Kantine's
 * `Dicken45 + Benito`, #1844), or a comma. The comma needs no padding, because the venue writes the
 * list both ways.
 */
private val SLOT_SEPARATOR = Regex("""${B2B_SEPARATOR.pattern}|\s+\+\s+|\s*,\s*""", RegexOption.IGNORE_CASE)
