package de.norm.events.scraper.soulcat

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import tools.jackson.databind.JsonNode

/**
 * Pure JSON parser for Soulcat's **The Events Calendar** REST API (`/wp-json/tribe/events/v1/events`).
 *
 * Every event carries a title, a start and an end, and nothing else: no description, image, price,
 * website, venue or organizer. So the title is read for everything:
 *
 * - **`DJ Krawallisch – 60s RnB, Soul & Blues`**: the part before the first dash names the DJs, split
 * on `&`, and the parts after it are the genre.
 * - **`60s RnB & Soul & Blues & RocknRoll – Bartenders Choice`**: a head without `DJ` names no act, and
 * is itself the genre, unless it is the whole title (`60s RnB & Soul`). The house genre `Soul` covers that.
 * - **The tail markers** `VINYL ONLY!`, `FREE ENTRY!` and `Dancers Welcome :)` are cut from the title.
 * `FREE ENTRY` marks the night free.
 * - **The bar also shows Werder Bremen matches**, filed under the `Fussball` category. Those are skipped.
 *
 * The `slug` is unique per date and identifies an event. Every night is a DJ night, so a [EventType.PARTY].
 *
 * @see SoulcatWebsiteImporter for the HTTP fetch orchestrator.
 * @see de.norm.events.scraper.parseTecPage for the page shape and the field readers.
 */
class SoulcatApiScraper {
    /** Parses one page of the events endpoint; [parseTecPage] says how a malformed page or event degrades. */
    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.SOULCAT, ::toScrapedEvent)

    /** Maps one API event, or `null` for a football match or an event without the slug, start or title that identify it. */
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val football = event.tecTermNames("categories").any { it.equals(FOOTBALL_CATEGORY, ignoreCase = true) }
        val slug = event.stringOrNull("slug")?.takeUnless { football }
        val start = event.tecDateTime("start_date")
        val rawTitle = event.tecText("title")
        if (slug == null || start == null || rawTitle == null) return null
        val title = withoutMarkers(rawTitle)
        val parts = title.split(SEGMENT_DASH)
        val djs = DJ_HEAD.matchEntire(parts.first())?.groupValues?.get(1)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }

        return ScrapedEvent(
            title = title,
            eventType = EventType.PARTY.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            sourceUrl = event.path("url").asString(""),
            sourceId = "${EventSource.SOULCAT.sourceIdPrefix}$slug",
            genre = genreOf(title, parts, djs != null),
            free = FREE_ENTRY.containsMatchIn(rawTitle),
            artists = djs?.split(NAME_SEPARATOR)?.map { ScrapedArtist(name = it.trim(), role = "DJ") }.orEmpty()
        )
    }

    /**
     * The segments after the DJs, or the head of a night with none. A head that is the whole title
     * gives none: the upsert drops a genre that repeats the title, and the house genre fills it.
     */
    private fun genreOf(
        title: String,
        parts: List<String>,
        namesDjs: Boolean
    ): String? = (if (namesDjs) parts.drop(1) else parts.take(1)).joinToString(", ").takeIf { it.isNotBlank() && it != title }

    /** [title] without the tail markers, and without the dashes and dots they leave at its end. */
    private fun withoutMarkers(title: String): String =
        MARKER
            .replace(title, " ")
            .replace(SPACES, " ")
            .trimEnd { it.isWhitespace() || it in TRAILING_LEFTOVERS }
}

/** The category the bar files its Werder Bremen screenings under. */
private const val FOOTBALL_CATEGORY = "Fussball"

/** The notes a title ends on, none of which belongs in it: `VINYL ONLY!`, `FREE ENTRY!`, `Dancers Welcome :)`. */
private val MARKER = Regex("""(?i)(?:vinyl only|free entry|dancers welcome)\s*[!.]*\s*(?:[:;]\))?""")

private val FREE_ENTRY = Regex("""(?i)free entry""")

private val SPACES = Regex("""\s{2,}""")

/** What a cut marker leaves behind: the dash before it, the `–>` arrow, the `….` ellipsis. */
private const val TRAILING_LEFTOVERS = "–—->….!"

/** The dash between the DJs and the genre, or the arrow (`–>`) one title uses. */
private val SEGMENT_DASH = Regex("""\s+[–—]>?\s+""")

/** A head that names DJs: `DJ Krawallisch`, `DJs KALULUWA & ICKX`. */
private val DJ_HEAD = Regex("""DJs?\s+(.+)""")

private val NAME_SEPARATOR = Regex("""\s+&\s+""")
