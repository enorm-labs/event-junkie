package de.norm.events.scraper

import de.norm.events.slug.SlugGenerator

// The crew a club night's title names as its host, credited as the night's promoter (#5080). The clubs that use it print
// no organiser field; the title is the only place the collective appears.

/**
 * The crews [title] names as its hosts: `Tresor New Faces hosted by Grab The Groove`, `MissVergnügen presents ALIEN GHOST`,
 * `VINTENOVE BAILES invites State OFFF`, `Symbiotikka meets Salty Pleasure`. A name in [venueNames] is the house itself,
 * and `<Venue> invites X` or `<Venue> meets X` bills X as an act, so neither gives a crew (`Tresor invites Surgeon`).
 */
fun hostingCrewsFromTitle(
    title: String,
    venueNames: Set<String>
): List<String> {
    val text = title.trim()
    val isVenue = { name: String -> venueNames.any { it.equals(name.trim(), ignoreCase = true) } }
    HOSTED_BY.find(text)?.let { return crewNames(it.groupValues[1], isVenue) }
    MEETS.find(text)?.let { match ->
        val (host, guest) = match.destructured
        return if (isVenue(host)) emptyList() else crewNames(host, isVenue) + crewNames(guest, isVenue)
    }
    val lead = (PRESENTS_LEAD.find(text) ?: INVITES_LEAD.find(text))?.groupValues?.get(1) ?: return emptyList()
    return if (isVenue(lead)) emptyList() else crewNames(lead, isVenue)
}

/**
 * The two crews of a `X x Y` title, `INFECTED x TORQUE`, for a club where every night is billed by its crews. Elsewhere
 * `D@W x RUTA` can as well be two acts, so a scraper opts in. A title with another marker goes to [hostingCrewsFromTitle].
 */
fun crewPairFromTitle(
    title: String,
    venueNames: Set<String>
): List<String> {
    val head = title.trim().split(CREW_TAIL, limit = 2).first()
    val parts = head.split(PAIR_SEPARATOR)
    if (parts.size < 2) return emptyList()
    return parts.flatMap { part -> crewNames(part) { name -> venueNames.any { it.equals(name.trim(), ignoreCase = true) } } }
}

/**
 * This event with the crews [hostingCrewsFromTitle] reads from its title as its promoters, or [crews] where a scraper reads
 * them itself, unless the source already credits one. A crew that is also a billed act is dropped: a promoter that only
 * repeats the line-up is a defect (#4043, #4184), and the import would drop a title-derived act whose name is a promoter.
 */
fun ScrapedEvent.withTitleCrews(
    venueNames: Set<String>,
    crews: List<String> = hostingCrewsFromTitle(title, venueNames)
): ScrapedEvent {
    if (promoters.isNotEmpty()) return this
    val acts = artists.map { SlugGenerator.slugify(it.name) }.toSet()
    val credited = crews.distinctBy(SlugGenerator::slugify).filterNot { SlugGenerator.slugify(it) in acts }
    return if (credited.isEmpty()) this else copy(promoters = credited)
}

/**
 * The names in [raw]: cut at the first tail marker, a trailing `invites…` dropped, split as a promoter credit and at a co-host's
 * ` x ` (`808 X Bout It`), an edition number dropped (`VANTA.NYX VI`). `Friends` and `Residents` name nobody.
 */
internal fun crewNames(
    raw: String,
    isVenue: (String) -> Boolean
): List<String> =
    splitPromoterCredit(raw.split(CREW_TAIL, limit = 2).first().replace(TRAILING_INVITES, ""))
        .flatMap { it.split(PAIR_SEPARATOR) }
        .map { it.trim(' ', '.', '…', '•', '¸').replace(EDITION, "") }
        .filter { name ->
            name.length in 2..MAX_CREW_LENGTH &&
                name.split(' ').size <= MAX_CREW_WORDS &&
                name.lowercase() !in NOBODY &&
                !isVenue(name)
        }

private const val MAX_CREW_LENGTH = 40
private const val MAX_CREW_WORDS = 5

private val NOBODY = setOf("friends", "freunde", "guests", "gäste", "residents", "family", "tba", "more tba")

/** A trailing edition: `VI`, `XII`, `#11`, `2026`. */
private val EDITION = Regex("""\s+(?:#?\d+|[IVX]{1,4})$""")

private val HOSTED_BY = Regex("""^.+?\s+hosted\s+by\s+(.+)$""", RegexOption.IGNORE_CASE)

private val MEETS = Regex("""^(.+?)\s+meets\s+(.+)$""", RegexOption.IGNORE_CASE)

/** `X presents …`, `X pres. …`, and the plural `X present: …` that only a colon makes safe. */
private val PRESENTS_LEAD = Regex("""^(.+?)\s+(?:(?:$PRESENTS_VERBS|pres\.|prs\.)\s*:?|present\s*:)\s+\S""", RegexOption.IGNORE_CASE)

private val INVITES_LEAD = Regex("""^(.+?)\s+invites(?!\p{L})""", RegexOption.IGNORE_CASE)

private val TRAILING_INVITES = Regex("""\s+invites(?!\p{L}).*$""", RegexOption.IGNORE_CASE)

/** Where a crew's name ends and the night's own words begin: a dash, a colon, `w/`, a star, a bracket, a slash run. */
private val CREW_TAIL = Regex("""\s+[-–—|]\s+|:\s|\s+w/\s|\s*[★☆]\s*|\s*\(|\s*///""")

private val PAIR_SEPARATOR = Regex("""\s+[x×]\s+""", RegexOption.IGNORE_CASE)
