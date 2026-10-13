package de.norm.events.scraper.pas

import de.norm.events.event.EventType
import de.norm.events.scraper.DOORS_LABELS
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.labelledClock
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

/**
 * Pure JSON parser for PAS's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **An event held elsewhere is skipped**: one filed under `PAS at another spaces / NOT in PAS`, one whose venue record
 *   names another place, or one whose title opens with the house and puts it `at` another place (`PAS event at Kallasch&!`).
 *   `DANCE at PAS` and `… Showcase at PAS Berlin` stay, and lose that tail.
 * - **The categories type the night**, music first. An event filed only under workshop, social or open call is out of scope.
 * - **The API time is the doors** when the description prints `Doors 19:30, music a bit later`; the start is then unknown.
 * - **The title is the lineup**: sets split by `/`, players by commas and `&`. A series or label opens the title before a colon
 *   (`Music for Cosmos: RAFGAK`), and an ensemble opens a later set before its players (`RAFGAK: Bryan Eubanks & …`).
 *   A surname billed alone (`Tuerlinckx & Cusack`) takes the full name from a credit line (`Anaïs Tuerlinckx – piano`).
 * - **The tags mix styles with formats**, so only the styles are the genre.
 */
class PasApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.PAS, ::toScrapedEvent)

    @Suppress("ReturnCount") // Guard clauses for the required fields and the two skips are clearer than nesting
    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val rawTitle = event.tecText("title")
        if (rawTitle == null) {
            logger.warn { "PAS event '$slug' has no title, skipping" }
            return null
        }
        val categories = event.tecTermNames("categories").map { it.lowercase() }
        heldElsewhere(rawTitle, categories, event.path("venue").tecText("venue"))?.let { reason ->
            logger.debug { "Skipping PAS event '$slug': $reason" }
            return null
        }
        if (categories.isNotEmpty() && categories.all { it in OUT_OF_SCOPE_CATEGORIES }) {
            logger.debug { "Skipping PAS event '$slug': filed only under ${categories.joinToString()}" }
            return null
        }
        val title = cleanEventTitle(rawTitle.replace(HOUSE_SUFFIX, ""))
        val eventType = typeOf(categories)
        val description = event.tecDescription()
        val doors = labelledClock(description, DOORS_LABELS)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val prices = event.path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }

        return ScrapedEvent(
            title = title,
            description = description,
            eventType = eventType ?: inferUnmarkedTitleType(title),
            typeIsFallback = eventType == null,
            eventDate = start.toLocalDate(),
            doorsTime = doors,
            startTime = labelledClock(description, START_LABELS) ?: start.toLocalTime().takeUnless { it == doors },
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = "${EventSource.PAS.sourceIdPrefix}$slug",
            ticketUrl = event.stringOrNull("website")?.takeIf { it.isNotBlank() },
            genre =
                event
                    .tecTermNames("tags")
                    .mapNotNull { STYLE_BY_TAG[it.lowercase()] }
                    .distinct()
                    .joinToString(", ")
                    .ifEmpty { null },
            pricePresale = prices.singleOrNull()?.takeIf { it.signum() > 0 },
            priceNote = event.tecText("cost")?.takeIf { prices.size > 1 },
            artists = if (eventType == EventType.CONCERT.name) billedActs(title, event.path("description").asString("")) else emptyList(),
            // The association's own organizer entry is the venue, not a promoter.
            promoters = event.tecOrganizerNames().filterNot { HOUSE_NAME.containsMatchIn(it) }
        )
    }

    /** Why the event is held somewhere other than PAS, or null when it is held at the house. */
    private fun heldElsewhere(
        title: String,
        categories: List<String>,
        venue: String?
    ): String? =
        when {
            ELSEWHERE_CATEGORY in categories -> "filed under '$ELSEWHERE_CATEGORY'"
            venue != null && !HOUSE_NAME.containsMatchIn(venue) -> "held at '$venue'"
            ELSEWHERE_TITLE.containsMatchIn(title) -> "the title places it elsewhere"
            else -> null
        }

    /** The type the categories name, music first, or null for an event filed under none of them. */
    private fun typeOf(categories: List<String>): String? =
        TYPE_BY_CATEGORY.firstNotNullOfOrNull { (names, type) -> type.takeIf { categories.any { category -> category in names } } }

    /** The acts the title bills, or none for a series night whose title is one name. */
    private fun billedActs(
        title: String,
        descriptionHtml: String
    ): List<ScrapedArtist> {
        if (!BILLING_SEPARATOR.containsMatchIn(title)) return emptyList()
        val billing =
            title
                .split(SET_SEPARATOR)
                .mapIndexed { index, set -> if (index == 0) set.substringAfter(": ") else set.substringBefore(":") }
                .joinToString(" / ") { it.replace(INSTRUMENT_NOTE, "") }
        val credited = creditedNames(descriptionHtml)
        return headlinersFromTitle(billing).map { act -> act.copy(name = fullName(act.name, credited)) }
    }

    /** The players the description credits one per line, `Name – instrument` or `Name: instrument`. */
    private fun creditedNames(descriptionHtml: String): List<String> =
        descriptionHtml
            .split(LINE_BREAK)
            .mapNotNull { line -> CREDIT_LINE.find(decodeHtmlEntities(line.replace(TAG, "")).trim())?.groupValues?.get(1) }

    /** A surname billed alone, as the one credited player of that surname; otherwise the act as billed. */
    private fun fullName(
        act: String,
        credited: List<String>
    ): String = if (' ' in act) act else credited.singleOrNull { it.substringAfterLast(' ').equals(act, ignoreCase = true) } ?: act

    private companion object {
        /** The house as its venue records and organizers name it: `PAS`, `PAS Riverside`, `PAS e.V.`. */
        val HOUSE_NAME = Regex("""^PAS\b""", RegexOption.IGNORE_CASE)

        /** The category the house files its guest appearances at other venues under. */
        const val ELSEWHERE_CATEGORY = "pas at another spaces / not in pas"

        /** A title that opens with the house and puts it `at` another venue: `PAS Berlin at ZK/U OPENHAUS event`. */
        val ELSEWHERE_TITLE = Regex("""^PAS\b.*?\bat\s+(?!PAS\b)\S""", RegexOption.IGNORE_CASE)

        /** The venue's own name closing a title: `DISSIDENTS LXV at PAS`, `Party Perfect Label Showcase at PAS Berlin`. */
        val HOUSE_SUFFIX = Regex("""\s+at\s+PAS(?:\s+Berlin)?\.?$""", RegexOption.IGNORE_CASE)

        /** A title that bills more than one act, or an act after a series or label. A bare name is a series night. */
        val BILLING_SEPARATOR = Regex("""[/,&+:]""")

        val SET_SEPARATOR = Regex("""\s+/\s+""")

        /** A player's instrument after the name, `Beth Ellen Rosenbaum (piano)`; a country code such as `(CH)` stays. */
        val INSTRUMENT_NOTE = Regex("""\s+\(\p{Ll}[^)]*\)""")

        /** Two to four capitalised words, then a dash or a colon and the instrument. */
        val CREDIT_LINE = Regex("""^(\p{Lu}[\p{L}.'-]*(?:\s+\p{Lu}[\p{L}.'-]*){1,3})\s*[–:-]\s*\p{L}""")

        val LINE_BREAK = Regex("""<br\s*/?>|</p>|</li>|\n""")
        val TAG = Regex("""<[^>]+>""")

        val OUT_OF_SCOPE_CATEGORIES = setOf("workshop", "sprachcafe / social", "open call")

        val TYPE_BY_CATEGORY =
            listOf(
                setOf("concert", "music for the cosmos", "jam") to EventType.CONCERT.name,
                setOf("club / party") to EventType.PARTY.name,
                setOf("performance", "theatre", "dance") to EventType.SHOW.name,
                setOf("exhibition", "installation") to EventType.EXHIBITION.name,
                setOf("film screening") to EventType.SCREENING.name,
                setOf("poetry", "public talk") to EventType.READING.name
            )

        val STYLE_BY_TAG =
            mapOf(
                "experimental" to "Experimental",
                "free improvisation" to "Free Improvisation",
                "improvised" to "Free Improvisation",
                "free jazz" to "Free Jazz",
                "jazz" to "Jazz",
                "noise" to "Noise",
                "electroacoustic" to "Electroacoustic",
                "ambient" to "Ambient",
                "techno" to "Techno",
                "electronic music" to "Electronic",
                "electronica" to "Electronica",
                "electornica" to "Electronica",
                "fusion" to "Fusion",
                "pop" to "Pop",
                "rock" to "Rock"
            )
    }
}
