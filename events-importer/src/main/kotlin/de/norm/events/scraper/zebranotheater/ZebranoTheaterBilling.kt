package de.norm.events.scraper.zebranotheater

import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.isNonArtistName

/**
 * The performers of a Zebrano-Theater billing. A title reads `<performers>: <programme>`
 * (`Ahne: Ist das noch Jazz?`), a host's own show `Sebastian Krämers Club Genie und Wahnsinn`, or a
 * plain duo (`Ulrich Zehfuß und Claudia Fink`). A subtitle line `mit A, B und C` names a reading
 * stage's cast; `(Gast: X)`, `Spezialgast: X` and the club show's `(Antragsteller: X)` name a guest.
 * A head that is not name-shaped (`Hörspiele zum Zugucken:`) is a series, not an act.
 */
internal object ZebranoTheaterBilling {
    fun artists(
        title: String,
        subtitleLines: List<String>,
        isConcert: Boolean
    ): List<ScrapedArtist> {
        val (billed, featured) = headliners(title, isConcert).partition { !it.second }
        val heads = billed.map { it.first } + subtitleLines.flatMap(::castOf)
        val guests = featured.map { it.first } + subtitleLines.flatMap(::guestsOf)
        return (heads.map { ScrapedArtist(name = it) } + guests.map { ScrapedArtist(name = it, role = "SUPPORT") })
            .filterNot { isNonArtistName(it.name) }
            .distinctBy { it.name.lowercase() }
    }

    /** Each name of the title, paired with whether a `feat.` introduced it. */
    private fun headliners(
        title: String,
        isConcert: Boolean
    ): List<Pair<String, Boolean>> {
        val head = title.substringBefore(':', missingDelimiterValue = "").trim().replace(IN_CONCERT, "")
        val host = HOST_SHOW.matchEntire(title)?.groupValues?.get(1)
        val billing =
            when {
                head.isNotEmpty() -> head

                host != null -> return listOf(host to false)

                // Only a concert bills its act by name alone; a play's title is not a person (`Die Weihnachtsgeschichte von Charles Dickens`).
                isConcert -> title

                else -> return emptyList()
            }
        if (!NAMES.matches(billing)) return emptyList()
        val parts = billing.split(FEAT)
        return split(parts.first()).map { it to false } + parts.drop(1).map { it.trim() to true }
    }

    private fun castOf(line: String): List<String> = CAST.matchEntire(line)?.let { split(it.groupValues[1]) }.orEmpty()

    private fun guestsOf(line: String): List<String> = GUEST.find(line)?.let { split(it.groupValues[1]) }.orEmpty()

    /** Splits on commas and `und`, never on `&`, which joins a duo's own name (`Ulan & Bator`). */
    private fun split(list: String): List<String> = list.split(LIST_SEPARATOR).map { it.trim() }.filter { NAMES.matches(it) }

    private const val NAME_WORD = """\p{Lu}[\p{L}'.-]*"""
    private const val JOINER = """und|&|feat\.|von|van|de|la|le|di"""

    /** Capitalised words, joined by `und`, `&`, `feat.` or a name particle. */
    private val NAMES = Regex("""$NAME_WORD(?:\s+(?:$NAME_WORD|$JOINER))*""")

    private val LIST_SEPARATOR = Regex(""",\s*|\s+und\s+""")

    private val FEAT = Regex("""\s+feat\.\s+""")

    private val IN_CONCERT = Regex("""\s+im Konzert$""")

    /** `Sebastian Krämers Club …`: a two- or three-word name in the genitive before the show's own name. */
    private val HOST_SHOW = Regex("""^((?:$NAME_WORD\s+){1,2}\p{Lu}[\p{L}-]*?)s\s+\p{Lu}.*$""")

    private val CAST = Regex("""^mit\s+(.+)$""")

    private val GUEST = Regex("""(?:Spezialgast|Gast|Gästin|Gäste|Antragsteller(?:in)?)\s*:\s*([^)]+)""")
}
