package de.norm.events.scraper.koepi

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.radar.RadarGroupImporter.Lineup

/**
 * KØPI's bill, read from a radar description. The titles are generic ("Konzert im K (F)"), and the
 * description lists each act as a quoted name followed by its genre and home in brackets:
 * `"HOSTIUM" (Trash Punk / HC, Bogotá, Colombia) - "FUNERAL DAMAGE" (Crust, Berlin)`.
 *
 * **The bracket is what makes a quote an act.** A quoted line without one is the night's name or a
 * slogan (`"B-day Bash - …"`, `"OUR ROOTS ARE STRONGER …"`). The first part of the bracket is the
 * genre, and the rest, where present, the act's home town. A DJ slot names its act in the bracket:
 * `"PUNK DJs" (BALADA GANGSTER)` bills Balada Gangster as a DJ, with no genre.
 */
internal fun parseKoepiBill(description: String?): Lineup {
    if (description == null) return Lineup()
    val billed = BILLED_ACT.findAll(description).map { it.groupValues[1].trim() to it.groupValues[2].trim() }.toList()
    val artists = mutableListOf<ScrapedArtist>()
    val genres = mutableListOf<String>()
    billed.forEach { (quoted, bracket) ->
        if (DJ_SLOT.matches(quoted)) {
            bracket.takeIf { it.isNotBlank() && !isNonArtistName(it) }?.let { artists += ScrapedArtist(name = it, role = "DJ") }
        } else if (!isNonArtistName(quoted)) {
            artists += ScrapedArtist(name = quoted, role = if (artists.none { it.role == "HEADLINER" }) "HEADLINER" else "SUPPORT")
            bracket
                .substringBefore(',')
                .trim()
                .takeIf { it.isNotBlank() }
                ?.let { genres += it }
        }
    }
    return Lineup(
        artists = artists.distinctBy { it.name.lowercase() },
        genre = genres.distinct().joinToString(", ").takeIf { it.isNotBlank() }
    )
}

/** A quoted name, then its bracket, with at most whitespace between: `"Languid" (Raw Hardcore Punk, Canada)`. */
private val BILLED_ACT = Regex(""""([^"\n]{1,80})"\s*\(([^()\n]{1,120})\)""")

/** A quoted DJ slot rather than an act: `PUNK DJs`, `DJs`, `DJ`. */
private val DJ_SLOT = Regex("""(?i)(?:[\p{L} ]+\s)?djs?""")
