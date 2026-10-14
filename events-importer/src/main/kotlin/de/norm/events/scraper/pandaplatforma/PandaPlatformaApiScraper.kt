package de.norm.events.scraper.pandaplatforma

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.hasSoldOutMarker
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure JSON parser for PANDA platforma's **The Events Calendar** REST API; the page shape and
 * the field readers are [parseTecPage]'s.
 *
 * - **The categories are the house series** (`PANDAjazz`, `PANDAtheatre`, `PANDAtext` …), and
 * they type the night. A text or poetry series outranks music: a singer's poetry evening is filed
 * under both, and it is a reading.
 * - **A `PANDAgoes` night runs at another club** and names it as the event's `venue`, so only the
 * events at a venue record named for the house are kept.
 * - **A concert credits its players one per line**, often once per language: `<strong>Name</strong> –
 * instrument`, `<strong>Name</strong>: instrument`, `Name: instrument` or `Name – instrument`. A
 * shape other than the first needs a name of two to four capitalised words, so `<strong>Incense</strong>:
 * Frankincense` and `Credits: <photographer>` stay out. Those lines are the lineup.
 * - **A concert without a credit line bills its title** (`Igr Titov (acoustic)`), unless the title
 * holds a colon: `Peyvand #3: Iran Ava – …` names a series night, and its players are only in the prose.
 * In `LLULL MACHINES PRESENT DEAR BOY` the act presents a work, so the work comes off.
 * - **`cost_details` holds one value, two for a range, `0` for free.**
 */
class PandaPlatformaApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.PANDA_PLATFORMA, ::toScrapedEvent)

    @Suppress("ReturnCount") // Guard clauses for the required fields and the venue are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val title = event.tecText("title")?.let { cleanEventTitle(it.replace(SERIES_PREFIX, "")) }
        if (title.isNullOrBlank()) {
            logger.warn { "PANDA platforma event '$slug' has no title, skipping" }
            return null
        }
        if (event.path("venue").tecText("venue")?.equals(HOUSE_NAME, ignoreCase = true) != true) {
            logger.debug { "Skipping PANDA platforma event '$title': it runs at another venue" }
            return null
        }
        val categories = event.tecTermNames("categories").map { it.lowercase() }
        val eventType = typeOf(categories)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val prices = event.path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            eventType = eventType ?: titleType(title),
            typeIsFallback = eventType == null,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.PANDA_PLATFORMA.sourceIdPrefix}$slug",
            ticketUrl = event.stringOrNull("website")?.takeIf { it.isNotBlank() },
            genre = categories.mapNotNull { STYLE_BY_CATEGORY[it] }.joinToString(", ").ifEmpty { null },
            pricePresale = prices.singleOrNull()?.takeIf { it.signum() > 0 },
            priceNote = event.tecText("cost")?.takeIf { prices.size > 1 },
            free = prices.singleOrNull()?.compareTo(BigDecimal.ZERO) == 0,
            soldOut = hasSoldOutMarker(title),
            artists = if (eventType == EventType.CONCERT.name) concertActs(title, event) else emptyList(),
            // The association's own organizer entry is the venue, not a promoter.
            promoters = event.tecOrganizerNames().filterNot { it.startsWith(HOUSE_NAME, ignoreCase = true) }
        )
    }

    /** The type the series name, or null for an event filed under none. */
    private fun typeOf(categories: List<String>): String? =
        when {
            categories.any { it in TEXT_CATEGORIES } -> EventType.READING.name
            "pandatheatre" in categories -> EventType.SHOW.name
            "pandaexhibition" in categories -> EventType.EXHIBITION.name
            categories.any { it in MUSIC_CATEGORIES } -> EventType.CONCERT.name
            else -> null
        }

    /** The type an uncategorised title names: a `staged reading` in English, else the shared title cues (#2992). */
    private fun titleType(title: String): String = if (READING_TITLE.containsMatchIn(title)) EventType.READING.name else inferUnmarkedTitleType(title)

    /** The credited players, or the title's acts when no line credits one. */
    private fun concertActs(
        title: String,
        event: JsonNode
    ): List<ScrapedArtist> =
        creditedPlayers(event.path("description").asString("")).ifEmpty {
            if (':' in title) emptyList() else headlinersFromTitle(title.replace(PRESENTED_WORK, ""))
        }

    /** The players of a credit list, one per line, each once. */
    private fun creditedPlayers(description: String): List<ScrapedArtist> =
        description
            .split(LINE_BREAK)
            .mapNotNull(::creditedName)
            .map { decodeHtmlEntities(it).trim() }
            .filterNot { it.isBlank() || isNonArtistName(it) }
            .distinctBy { it.lowercase() }
            .map { ScrapedArtist(name = it) }

    private fun creditedName(line: String): String? =
        BOLD_DASH_CREDIT.find(line)?.groupValues?.get(1) ?: plainCreditedName(decodeHtmlEntities(line.replace(TAG, "")).replace('\u00A0', ' '))

    /** A name that is not bold must look like a person's, two to four words opening in capitals. */
    private fun plainCreditedName(text: String): String? =
        PLAIN_CREDIT
            .find(text.trim())
            ?.takeIf { text.length <= MAX_CREDIT_LINE }
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { name ->
                val words = name.split(WHITESPACE)
                words.size in PERSON_NAME_WORDS && words.first().first().isUpperCase() && words.last().first().isUpperCase()
            }

    private companion object {
        /** The house's name on its venue records, of which there are two; a `PANDAgoes` night names the club it visits. */
        const val HOUSE_NAME = "PANDA platforma"

        /** A series label opening the title, `PANDAjazz: Die Ursonate`. */
        val SERIES_PREFIX = Regex("""^PANDA\p{L}+:\s+""")

        /** One credited player: a bold name, a dash, then the instrument. */
        val BOLD_DASH_CREDIT = Regex("""<strong>([^<]{2,60})</strong>\s*(?:&#8211;|–|-)\s*\p{L}""")

        /** A credit with its tags gone: a name, then a colon or a spaced dash, then the instrument. */
        val PLAIN_CREDIT = Regex("""^(\p{Lu}[^:–,\d]{1,58}?)\s*(?::\s*|\s[–-]\s+)\p{L}""")

        /** The work an act presents, after the act: ` PRESENT DEAR BOY`. */
        val PRESENTED_WORK = Regex("""\s+presents?\s+.+$""", RegexOption.IGNORE_CASE)

        val LINE_BREAK = Regex("""<br\s*/?>|</p>|</div>|</li>|\n""")
        val TAG = Regex("""<[^>]+>""")
        val WHITESPACE = Regex("""\s+""")

        /** Longer than any credit line seen; a longer one is prose that happens to hold a colon. */
        const val MAX_CREDIT_LINE = 120

        val PERSON_NAME_WORDS = 2..4

        val TEXT_CATEGORIES = setOf("pandatext", "pandapoetry")

        /** The house titles in English as often as in German: `“28”: staged reading`. */
        val READING_TITLE = Regex("""\breading\b""", RegexOption.IGNORE_CASE)
        val MUSIC_CATEGORIES = setOf("pandamusic", "pandajazz", "pandaexperimental", "pandaglobal")
        val STYLE_BY_CATEGORY = mapOf("pandajazz" to "Jazz", "pandaexperimental" to "Experimental", "pandaglobal" to "World")
    }
}
