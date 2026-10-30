package de.norm.events.scraper.abstand

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.radar.RadarGroupImporter.Lineup
import de.norm.events.scraper.splitSupportActs

/**
 * Abstand's bill, read from a radar description. Each band is a name with a bracket after it, in a list after
 * `mit` or one per `- ` line: `Konzert mit Accion Mutante (Crust legends Stuttgart), Necromorph (…) und Iron
 * Kindl Pest (…)`. **The bracket is what makes a name a band**: prose of any other shape bills no one. The bracket
 * mixes genre and home town, so no genre is read. A `mit DJs A, B und C` list bills the DJs.
 */
internal fun parseAbstandBill(description: String?): Lineup {
    // radar's editor puts a non-breaking space after `mit`, which `\s` does not match.
    val text = description?.replace('\u00A0', ' ') ?: return Lineup()
    val bands =
        ACT_LIST
            .findAll(text)
            .flatMap { list -> BRACKETED_NAME.findAll(list.groupValues[1]).map { it.groupValues[1].replace(LIST_JOINER, "").trim() } }
            .filterNot { it.isBlank() || isNonArtistName(it) }
            .toList()
    val djs = DJ_LIST.findAll(text).flatMap { splitSupportActs(it.groupValues[1]) }.filterNot(::isNonArtistName)
    val artists =
        bands.mapIndexed { index, name -> ScrapedArtist(name = name, role = if (index == 0) ArtistRole.HEADLINER else ArtistRole.SUPPORT) } +
            djs.map { ScrapedArtist(name = it, role = ArtistRole.DJ) }
    return Lineup(artists = artists.distinctBy { it.name.lowercase() })
}

/** A run of `<name> (<bracket>)` items after `mit` or a `- ` line start, joined by `,`, `/` or `und`. */
private val ACT_LIST =
    Regex("""(?:\bmit|^\s*-)\s+((?:[^,()/\n]+?\s*\([^()\n]*\)\s*(?:,|/|\bund\b)?\s*)+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

/** One `<name> (<bracket>)` item of an [ACT_LIST]. */
private val BRACKETED_NAME = Regex("""([^,()/\n]+?)\s*\([^()\n]*\)""")

/** The `und` that joins the last item of a list, or the dash of a `- ` line, left in front of a name. */
private val LIST_JOINER = Regex("""^\s*(?:und\s+|-\s*)""", RegexOption.IGNORE_CASE)

/** `mit DJs A, B und C`, up to the end of the sentence. */
private val DJ_LIST = Regex("""\bmit\s+DJs?\s+([^.!?\n]+)""", RegexOption.IGNORE_CASE)
