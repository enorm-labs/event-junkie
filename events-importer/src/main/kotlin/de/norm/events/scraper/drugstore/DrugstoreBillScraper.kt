package de.norm.events.scraper.drugstore

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.radar.RadarGroupImporter.Lineup

/**
 * Drugstore's bill, read from a radar description. Most nights put the acts on one line that opens
 * with `Featuring:` or `w/` and joins them with ` + `, each act followed by its genre and home:
 * `w/ Stoned Medusa (Psych Doom // Berlin) + Witchrot // Fuzzed out PsychDoom // Kanada`.
 *
 * **The opening word is what makes a line a bill.** Without it the acts sit in prose of no fixed
 * shape (`CAYENNE + SEITENSPRUNG AM DIENSTAG`), and no one is billed. The genre is the bracket, or
 * the first `//` segment after the name, up to the next `//`.
 */
internal fun parseDrugstoreBill(description: String?): Lineup {
    val line = description?.let { BILL_LINE.find(it) }?.groupValues?.get(1) ?: return Lineup()
    val billed = line.split(ACT_SEPARATOR).mapNotNull(::parseAct).filterNot { (name, _) -> isNonArtistName(name) }
    val artists =
        billed.map { it.first }.distinctBy { it.lowercase() }.mapIndexed { index, name ->
            ScrapedArtist(name = name, role = if (index == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT)
        }
    return Lineup(
        artists = artists,
        genre =
            billed
                .mapNotNull { it.second }
                .distinct()
                .joinToString(", ")
                .takeIf { it.isNotBlank() }
    )
}

/** One act and its genre: `Pyre (Hardcore)`, `Acid Throne (Blackened Doom // UK)`, `Witchrot // Fuzzed out PsychDoom // Kanada`. */
private fun parseAct(part: String): Pair<String, String?>? {
    val bracket = BRACKETED_ACT.matchEntire(part.trim())
    val (name, details) =
        if (bracket != null) {
            bracket.groupValues[1] to bracket.groupValues[2]
        } else {
            part.substringBefore(GENRE_SEPARATOR) to part.substringAfter(GENRE_SEPARATOR, "")
        }
    val act = name.trim().blankToNull() ?: return null
    return act to details.substringBefore(GENRE_SEPARATOR).trim().blankToNull()
}

/** The bill line, from its opening word to the end of the line. */
private val BILL_LINE = Regex("""(?im)^\s*(?:featuring:|w/)\s*(.+)$""")

/** The ` + ` between two acts. radar writes a non-breaking space after the `+`, which `\s` does not match. */
private val ACT_SEPARATOR = Regex("""[\s\u00A0]+\+[\s\u00A0]+""")

/** A name and its bracket: `Sternenwarte (Dungeon Synth / Noise // Berlin)`. */
private val BRACKETED_ACT = Regex("""(.+?)\s*\(([^()]*)\)\s*""")

/** The `//` between an act's name, genre and home. */
private const val GENRE_SEPARATOR = "//"
