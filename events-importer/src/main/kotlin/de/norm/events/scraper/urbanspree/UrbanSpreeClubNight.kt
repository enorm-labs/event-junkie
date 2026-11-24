package de.norm.events.scraper.urbanspree

import de.norm.events.event.ArtistRole
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName

// The venue's club night, the Klubnacht. Each edition bills its DJs in a different shape (#1904,
// #2350, #3382, #3704, event 15276), so the shapes are tried in turn. The prose `opens with Kim and Kyra` is not
// read: it has no fixed shape.

/** The role a club night's DJ is billed under. */
internal val DJ_ROLE = ArtistRole.DJ

/** The venue's word for its own club night, in any case. */
private val KLUBNACHT = Regex("""klubnacht""", RegexOption.IGNORE_CASE)

/** Whether [title] names the venue's club night, which is a `PARTY` whatever its category. */
internal fun isUrbanSpreeKlubnacht(title: String): Boolean = KLUBNACHT.containsMatchIn(title)

/**
 * The line-up of a night whose title bills nobody, from the first shape that names anyone: the
 * running order of DJ sets, a co-branded night's `Featuring` sentence, a Klubnacht title's
 * trailing bracket, then a Klubnacht's one bio per paragraph. [paragraphs] is the description with its lines.
 */
internal fun urbanSpreeClubNightLineUp(
    title: String,
    description: String?,
    paragraphs: String? = null
): List<ScrapedArtist> =
    urbanSpreeDjSets(description)
        .map { ScrapedArtist(name = it, role = DJ_ROLE) }
        .ifEmpty { urbanSpreeFeaturedActs(description).map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) } }
        .ifEmpty { urbanSpreeBracketDjs(title).map { ScrapedArtist(name = it, role = DJ_ROLE) } }
        .ifEmpty { urbanSpreeBioActs(title, paragraphs) }

/**
 * One `<name> — DJ set` entry of a club night's running order, in two shapes. The venue's paragraph
 * breaks fall inside entries, so the flat text is read. In a running order the name follows a clock
 * time or the previous entry's `DJ set`, which a lookahead leaves for the next match. Above a bio the
 * name is a run of capitals after a sentence end (`… textures. ALBERT KRAFT — DJ SET Originally …`,
 * #3704). No name holds a sentence end, so a bio is never read as the next entry's name.
 */
private val DJ_SET_ENTRY =
    Regex(
        """(?:(?:\d{1,2}:\d{2}|(?i:DJ set))\s+([^\s\d—–→](?:(?![.!?]\s)[^\d—–→])*?)""" +
            """|(?:^|[.!?])\s*(\p{Lu}[^\p{Ll}\d—–→.!?]*?))\s+[—–]\s+(?=(?i:DJ set)\b)"""
    )

/** The DJs a description bills as `<name> — DJ set`, in order, each once, a shouted name in the description's own spelling. */
internal fun urbanSpreeDjSets(description: String?): List<String> =
    description
        ?.let { text ->
            DJ_SET_ENTRY
                .findAll(text)
                .map { entry -> spelledAsInText(entry.groupValues[1].ifEmpty { entry.groupValues[2] }.trim(), text) }
                .distinct()
                .toList()
        }.orEmpty()

/**
 * [name] as [text] spells it elsewhere with a lowercase letter, or unchanged. A bio's heading shouts
 * the name (`MARIO MANARA (MNR)`), and the closing line spells it (`Mario Manara (MNR)`).
 */
private fun spelledAsInText(
    name: String,
    text: String
): String =
    if (name.any { it.isLowerCase() }) {
        name
    } else {
        Regex("""(?<!\p{L})${Regex.escape(name)}(?!\p{L})""", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.value }
            .firstOrNull { spelling -> spelling.any { it.isLowerCase() } }
            ?: name
    }

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

/**
 * The first sentence of an act's bio: the name, then `is` or a comma, then what the act does. Group 1 is the name and
 * group 2 the rest: `Katiusha, Berlin-based DJ and producer …`, `AIRSHPD is the live project of …`.
 */
private val BIO_OPENING = Regex("""^((?:[\p{Lu}\d][^\s,.]*)(?:\s+[\p{Lu}\d][^\s,.]*){0,3})(?:,|\s+is)\s+([^.!?]+)""")

/** What a bio's first sentence calls an act. */
private val BIO_ROLE = Regex("""\b(?:DJ|producer|live\s+project)\b""", RegexOption.IGNORE_CASE)

private val LIVE_PROJECT = Regex("""\blive\s+project\b""", RegexOption.IGNORE_CASE)

/** The fewest bios that make a line-up; one alone can describe the host. */
private const val MIN_BIOS = 2

/** The acts of a Klubnacht whose description gives each one a paragraph with its bio (event 15276). */
internal fun urbanSpreeBioActs(
    title: String,
    paragraphs: String?
): List<ScrapedArtist> =
    paragraphs
        ?.takeIf { isUrbanSpreeKlubnacht(title) }
        ?.lines()
        ?.mapNotNull { line -> BIO_OPENING.find(line.trim()) }
        ?.filter { bio -> BIO_ROLE.containsMatchIn(bio.groupValues[2]) && !isUrbanSpreeKlubnacht(bio.groupValues[1]) && !isNonArtistName(bio.groupValues[1]) }
        ?.map { bio -> ScrapedArtist(name = bio.groupValues[1], role = if (LIVE_PROJECT.containsMatchIn(bio.groupValues[2])) ArtistRole.LIVE else DJ_ROLE) }
        ?.distinctBy { it.name }
        ?.takeIf { it.size >= MIN_BIOS }
        .orEmpty()
