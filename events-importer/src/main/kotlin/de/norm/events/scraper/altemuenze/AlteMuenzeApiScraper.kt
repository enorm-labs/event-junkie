package de.norm.events.scraper.altemuenze

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.billsTradeFairOrConference
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecCostAmounts
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecFromPrice
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import java.math.BigDecimal

/**
 * Pure JSON parser for Alte Münze's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **The categories type the event**, festival and club first, then staged work over music: the opera days are filed under
 *   `Konzert` and `Theater`, and they are staged. An event filed only under a participation format (`Führung`, `Workshop`,
 *   `Sport`) is out of scope (EVENT_SCOPE.md §3.2), and so is one with no category: the flea market carries none.
 * - **A brunch or a conference (`Tagung`) is out whatever else it is filed under**: the drag brunch is a daytime meal with a show.
 * - **A recurring event's occurrences share one slug**, so the identity is the URL path, which adds the date (`…/2026-08-25/`)
 *   and, for two shows a day, a number (`…/2026-05-30/2/`).
 * - **An all-day event has no time**: the API sets `00:00` to `23:59`, and that run is the exhibition's or festival's dates.
 * - **A status opens the title before `//`** (`SOLD OUT // …`, `CANCELLED // …`); a series opens it the same way (`GATE TO // …`).
 */
class AlteMuenzeApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.ALTE_MUENZE, ::toScrapedEvent)

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("url")?.let(::occurrenceSlug) ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val rawTitle = event.tecText("title")
        if (rawTitle == null) {
            logger.warn { "Alte Münze event '$slug' has no title, skipping" }
            return null
        }
        val categories = event.tecTermNames("categories").map { it.lowercase() }
        val eventType = typeOf(categories)
        if (eventType == null || !isProgramme(categories, rawTitle)) {
            logger.debug { "Skipping Alte Münze event '$slug': filed under '${categories.joinToString()}'" }
            return null
        }
        val statusMarker = STATUS_PREFIX.find(rawTitle)?.groupValues?.get(1)
        val cancelled = statusMarker != null && CANCELLED.containsMatchIn(statusMarker)
        val title = cleanEventTitle(rawTitle.replace(STATUS_PREFIX, ""))
        val allDay = event.path("all_day").asBoolean(false)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val prices = event.tecCostAmounts()

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            eventType = eventType,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime().takeUnless { allDay },
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime()?.takeUnless { allDay },
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.path("url").asString(""),
            sourceId = "${EventSource.ALTE_MUENZE.sourceIdPrefix}$slug",
            ticketUrl = event.stringOrNull("website")?.takeIf { it.startsWith("http") && !NOT_A_TICKET_LINK.containsMatchIn(it) },
            pricePresale = tecFromPrice(prices),
            priceNote = event.tecText("cost")?.takeIf { prices.size > 1 },
            free = prices.singleOrNull()?.compareTo(BigDecimal.ZERO) == 0,
            soldOut = statusMarker != null && !cancelled,
            status = if (cancelled) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
            // The house's own organizer entry is the venue, not a promoter.
            promoters = event.tecOrganizerNames().filterNot { it.equals(HOUSE_NAME, ignoreCase = true) }
        )
    }

    /** The path after `/event/`, slashes as dashes: `opera-days-at-alte-muenze-2026-08-25`. */
    private fun occurrenceSlug(url: String): String? =
        url
            .substringAfter("/event/", "")
            .trim('/')
            .replace('/', '-')
            .blankToNull()

    /** Whether an event with a type is programme: not a brunch, not a conference by category or by title. */
    private fun isProgramme(
        categories: List<String>,
        title: String
    ): Boolean = categories.none { it in EXCLUDED_CATEGORIES } && !billsTradeFairOrConference(title, null)

    /** The type the first matching category names, or null for an event filed under none of them. */
    private fun typeOf(categories: List<String>): String? =
        TYPE_BY_CATEGORY.firstNotNullOfOrNull { (names, type) -> type.takeIf { categories.any { category -> category in names } } }

    private companion object {
        const val HOUSE_NAME = "Alte Münze"

        /** A status before the title's `//`: `SOLD OUT // Das Land, das ich liebe`. */
        val STATUS_PREFIX = Regex("""^\s*(sold\s?out|ausverkauft|cancell?ed|abgesagt)\s*//\s*""", RegexOption.IGNORE_CASE)
        val EXCLUDED_CATEGORIES = setOf("brunch", "tagung")

        val CANCELLED = Regex("""cancel|abgesagt""", RegexOption.IGNORE_CASE)

        /** An event link that is a social profile or the house's own site, not a ticket shop. */
        val NOT_A_TICKET_LINK = Regex("""instagram\.com|facebook\.com|alte-muenze-berlin\.de""", RegexOption.IGNORE_CASE)

        val TYPE_BY_CATEGORY =
            listOf(
                setOf("festival") to EventType.FESTIVAL.name,
                setOf("club") to EventType.PARTY.name,
                setOf("theater") to EventType.SHOW.name,
                setOf("konzert") to EventType.CONCERT.name,
                setOf("performance", "tanz") to EventType.SHOW.name,
                setOf("screening") to EventType.SCREENING.name,
                setOf("ausstellung", "installation") to EventType.EXHIBITION.name
            )
    }
}
