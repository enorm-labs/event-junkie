package de.norm.events.scraper.urbanspree

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName

// The venue's club night, the Klubnacht. Each edition bills its DJs in a different shape (#1904,
// #2350, #3382), so the shapes are tried in turn. The prose `opens with Kim and Kyra` is not
// read: it has no fixed shape.

/** The role a club night's DJ is billed under. */
internal const val DJ_ROLE = "DJ"

/** The venue's word for its own club night, in any case. */
private val KLUBNACHT = Regex("""klubnacht""", RegexOption.IGNORE_CASE)

/** Whether [title] names the venue's club night, which is a `PARTY` whatever its category. */
internal fun isUrbanSpreeKlubnacht(title: String): Boolean = KLUBNACHT.containsMatchIn(title)

/**
 * The line-up of a night whose title bills nobody, from the first shape that names anyone: the
 * running order of DJ sets, a co-branded night's `Featuring` sentence, then a Klubnacht title's
 * trailing bracket.
 */
internal fun urbanSpreeClubNightLineUp(
    title: String,
    description: String?
): List<ScrapedArtist> =
    urbanSpreeDjSets(description)
        .map { ScrapedArtist(name = it, role = DJ_ROLE) }
        .ifEmpty { urbanSpreeFeaturedActs(description).map { ScrapedArtist(name = it, role = "HEADLINER") } }
        .ifEmpty { urbanSpreeBracketDjs(title).map { ScrapedArtist(name = it, role = DJ_ROLE) } }

/** The bracket that ends a title: `Urban Spree KLUBNACHT (Kim, Kyra)`. */
private val TRAILING_BRACKET = Regex("""\(([^()]+)\)\s*$""")

/** The separators of a bracket line-up: a comma or an ampersand. */
private val BRACKET_LIST_SEPARATOR = Regex("""\s*[,&]\s*""")

/**
 * The DJs a Klubnacht title lists in its trailing bracket, in order, each once. A title that is
 * no Klubnacht gives none: a concert's bracket is a genre or a country (`(Dark Wave IT)`).
 */
internal fun urbanSpreeBracketDjs(title: String): List<String> =
    TRAILING_BRACKET
        .find(title)
        ?.takeIf { isUrbanSpreeKlubnacht(title) }
        ?.groupValues
        ?.get(1)
        ?.split(BRACKET_LIST_SEPARATOR)
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() && !isNonArtistName(it) }
        ?.distinct()
        .orEmpty()
