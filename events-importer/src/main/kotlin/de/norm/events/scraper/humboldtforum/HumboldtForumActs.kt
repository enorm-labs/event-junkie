package de.norm.events.scraper.humboldtforum

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.commaListActs
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.splitSegmentOnConjunctions

/**
 * The acts of a concert or a DJ night, from its `acf.subline` first and its title second. Every other type bills nobody: a
 * reading's or a talk's names are speakers, and the API has no line-up field (#5065).
 *
 * - `Mit FAYIM, OMAR KTD und SXCL` bills the list.
 * - `Ein Konzert … von Leonel Vásquez` bills the capitalised name after the last `von`, but not after `Musik von` or
 *   `Werke von`, which name a composer.
 * - `Musical Belongings VIII: Dschingis Lab` bills the part after a roman episode number.
 * - `DJ Maurício Fleury` on a party bills the DJ.
 * - Any other concert title goes through [buildArtistsForEventType], unless it names the house (`Queer Pop bei BERLIN GLOBAL`).
 */
internal fun actsOf(
    type: EventType,
    title: String,
    subline: String?
): List<ScrapedArtist> {
    val role =
        when (type) {
            EventType.CONCERT -> ArtistRole.HEADLINER
            EventType.PARTY -> ArtistRole.DJ
            else -> return emptyList()
        }
    val fromSubline = subline?.let(::sublineActs)
    val acts =
        when {
            !fromSubline.isNullOrEmpty() -> fromSubline.map { ScrapedArtist(name = it, role = role) }
            else -> titleActs(type, title, subline, role)
        }
    return acts.filterNot { isNonArtistName(it.name) }.distinctBy { it.name.lowercase() }
}

private fun sublineActs(subline: String): List<String>? {
    MIT_LIST.find(subline)?.takeUnless { CREDIT.containsMatchIn(it.groupValues[1]) }?.let { match ->
        val list = match.groupValues[1].trim()
        return (commaListActs(list) ?: list.split(',').flatMap { splitSegmentOnConjunctions(it.trim()) })
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
    return VON_NAME.find(subline)?.let { listOf(it.groupValues[1]) }
}

private fun titleActs(
    type: EventType,
    title: String,
    subline: String?,
    role: ArtistRole
): List<ScrapedArtist> {
    val named =
        when (type) {
            EventType.PARTY -> DJ_TITLE.find(title)
            else -> SERIES_EPISODE.find(title)
        }?.groupValues
            ?.get(1)
            ?.trim()
    return when {
        named != null -> listOf(ScrapedArtist(name = named, role = role, titleDerived = true))
        type == EventType.CONCERT && !HOUSE_TITLE.containsMatchIn(title) -> buildArtistsForEventType(title, subline, type.name)
        else -> emptyList()
    }
}

private val MIT_LIST = Regex("""^mit\s+(.+)$""", RegexOption.IGNORE_CASE)

/** `Mit Musik von …` is a credit in prose, not a line-up. */
private val CREDIT = Regex("""\bvon\b""")

/** One to four capitalised words ending the subline. `von` after `Musik` or `Werke` credits a composer, not the performer. */
private val VON_NAME =
    Regex("""(?<!Musik |Werke |Werken |Kompositionen |Liedern |Texten )\bvon\s+(\p{Lu}[\p{L}'.-]*(?:\s+\p{Lu}[\p{L}'.-]*){0,3})$""")

private val SERIES_EPISODE = Regex("""^\S.*?\s[IVXLC]+:\s*(\S.*)$""")

private val DJ_TITLE = Regex("""^DJ\s+(\S.*)$""", RegexOption.IGNORE_CASE)

/** A title that names one of the house's own programmes. */
private val HOUSE_TITLE = Regex("""\s(?:bei|im)\s+(?:BERLIN GLOBAL|Humboldt Forum)\b""", RegexOption.IGNORE_CASE)
