package de.norm.events.scraper

import de.norm.events.event.ArtistRole

/**
 * The acts of one slot in a DJ night's line-up: [ArtistRole.LIVE] where the venue marks a live set, else [ArtistRole.DJ].
 * The role is read before [clean] runs, because every cleanup cuts the marker off the name (#5487). A marker that ends
 * the slot covers all its acts, and one inside it covers its own act: `Shackleton live b2b Peverelist`.
 *
 * @param clean the venue's own cleanup of one name.
 */
fun parseActSlot(
    slot: String,
    joins: SlotJoins,
    stage: String? = null,
    clean: (String) -> String
): List<ScrapedArtist> {
    val slotIsLive = hasLiveMark(slot)
    return joins.split(slot).mapNotNull { part ->
        clean(part.trim())
            .trim()
            .takeIf { it.isNotBlank() && !isNonArtistName(it) }
            ?.let { ScrapedArtist(name = it, role = if (slotIsLive || hasLiveMark(part)) ArtistRole.LIVE else ArtistRole.DJ, stage = stage) }
    }
}

/** Whether [billing] ends in a live format, bracketed or bare: `Fireground [LIVE]`, `SERA (Live)`, `Cecilia Tosh LIVE Hybrid`. */
fun hasLiveMark(billing: String): Boolean = LIVE_MARK.containsMatchIn(billing)

private const val LIVE_FORMAT = """(?:hybrid\s+)?live(?:\s+(?:set|band|hybrid))?"""

/** End-anchored, so `Live Visuals` is no mark, and behind a name, so the act `Live` is none either. */
private val LIVE_MARK = Regex("""\S(?:\s*\[\s*$LIVE_FORMAT\s*]|\s*\(\s*$LIVE_FORMAT\s*\)|\s+$LIVE_FORMAT)\s*$""", RegexOption.IGNORE_CASE)
