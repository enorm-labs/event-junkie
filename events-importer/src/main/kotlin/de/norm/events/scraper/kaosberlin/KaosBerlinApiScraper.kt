package de.norm.events.scraper.kaosberlin

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.TecPage
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseTecPage
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.tecDateTime
import de.norm.events.scraper.tecDescription
import de.norm.events.scraper.tecImageUrl
import de.norm.events.scraper.tecOrganizerNames
import de.norm.events.scraper.tecSourceId
import de.norm.events.scraper.tecTermNames
import de.norm.events.scraper.tecText
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode

/**
 * Pure JSON parser for KAOS Berlin's **The Events Calendar** REST API; the page shape and the field readers are [parseTecPage]'s.
 *
 * - **The title can say concert where the category does not**: `Concert: A, B`, `A, B (Concert)`, `A + B live @ Kaos Berlin`.
 *   The marker goes from the title, and such a title is the lineup, joined even by a bare `+` (`slava bogu+blind arrowhead`).
 * - **Many events carry no category**, so the house's words type them: `open air` and `club` a party, `comedy` and `impro` a comedy night.
 * - **The queer meet-up is skipped**: filed under `Meetup`, or uncategorised and titled `Queerer Treff` or `Queerer Stammtisch`.
 * - **`website` is often the act's or promoter's social profile**, which is not a ticket link and goes. A ticket link copied
 *   from social media keeps its `&amp;` escapes and its `fbclid` and `utm_*` parameters; those go too.
 * - **`cost_details` holds words as well as amounts** (`spende`, `tba`, `frei`); only the amounts are prices.
 */
class KaosBerlinApiScraper {
    private val logger = KotlinLogging.logger {}

    fun scrapePage(json: String): TecPage = parseTecPage(json, EventSource.KAOS_BERLIN, ::toScrapedEvent)

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val slug = event.stringOrNull("slug") ?: return null
        val start = event.tecDateTime("start_date") ?: return null
        val rawTitle = event.tecText("title")
        if (rawTitle == null) {
            logger.warn { "KAOS Berlin event '$slug' has no title, skipping" }
            return null
        }
        val categories = event.tecTermNames("categories").map { it.lowercase() }
        if (MEETUP_CATEGORY in categories || MEETUP_TITLE.containsMatchIn(rawTitle)) {
            logger.debug { "Skipping KAOS Berlin event '$slug': a meet-up" }
            return null
        }
        val concertMarked = CONCERT_MARKERS.any { it.containsMatchIn(rawTitle) }
        val title = cleanEventTitle(CONCERT_MARKERS.fold(rawTitle) { text, marker -> text.replace(marker, "") })
        val categoryType = categories.firstNotNullOfOrNull { mapEventType(it, CATEGORY_TYPES) }
        val eventType = if (concertMarked) EventType.CONCERT.name else categoryType ?: inferVenueFormatType(title, null, TITLE_FORMATS)
        val end = event.tecDateTime("end_date")?.takeIf { it.isAfter(start) }
        val prices = event.path("cost_details").path("values").mapNotNull { it.asString("").toBigDecimalOrNull() }
        val cost = event.tecText("cost")

        return ScrapedEvent(
            title = title,
            description = event.tecDescription(),
            eventType = eventType,
            typeIsFallback = !concertMarked && categoryType == null,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = end?.toLocalDate(),
            endTime = end?.toLocalTime(),
            imageUrl = event.tecImageUrl(),
            sourceUrl = event.stringOrNull("url") ?: return null,
            sourceId = event.tecSourceId(EventSource.KAOS_BERLIN, slug),
            ticketUrl = ticketUrl(event),
            pricePresale = prices.singleOrNull()?.takeIf { it.signum() > 0 },
            priceNote = cost?.takeIf { prices.size > 1 || (prices.isEmpty() && !UNKNOWN_COST.matches(it)) },
            free = detectFree(pricePresale = prices.singleOrNull(), priceNote = cost),
            artists = if (eventType == EventType.CONCERT.name) billedActs(title, concertMarked) else emptyList(),
            // The association that runs the house is its own organizer entry, not a promoter.
            promoters = event.tecOrganizerNames().filterNot { HOUSE_NAME.containsMatchIn(it) }
        )
    }

    /** The acts of a concert whose title is its lineup; a bare series name (`MMM Event`) bills none. */
    private fun billedActs(
        title: String,
        concertMarked: Boolean
    ): List<ScrapedArtist> = if (concertMarked || BILLING_SEPARATOR.containsMatchIn(title)) headlinersFromTitle(title.replace(PLUS, " + ")) else emptyList()

    /** The event's `website` as a ticket link, unescaped and untracked, or null for a social profile or a host without a scheme. */
    private fun ticketUrl(event: JsonNode): String? {
        val url = event.stringOrNull("website")?.let(::decodeHtmlEntities)?.takeIf { it.startsWith("http") && !SOCIAL_PROFILE.containsMatchIn(it) }
        val stripped = url?.replace(TRACKING_PARAMETER, "") ?: return null
        return if ('?' in stripped) stripped else stripped.replaceFirst('&', '?')
    }

    private companion object {
        const val MEETUP_CATEGORY = "meetup"

        val MEETUP_TITLE = Regex("""\bqueere[rs]?\s+(?:treff|stammtisch)\b""", RegexOption.IGNORE_CASE)

        /** `Concert: …`, `… (Concert)`, and `… live @ Kaos Berlin`, each cut from the title. */
        val CONCERT_MARKERS =
            listOf(
                Regex("""^\s*Concert\s*:\s*""", RegexOption.IGNORE_CASE),
                Regex("""\s*\(Concert\)\s*$""", RegexOption.IGNORE_CASE),
                Regex("""\s+live\s+@\s*Kaos(?:\s+Berlin)?\s*$""", RegexOption.IGNORE_CASE)
            )

        val CATEGORY_TYPES =
            mapOf(
                "day rave" to EventType.PARTY.name,
                "open air" to EventType.PARTY.name,
                "kino" to EventType.SCREENING.name
            )

        /** The house's own format words, checked before the shared title cues; `club` is safe here, where no band plays under it. */
        val TITLE_FORMATS =
            linkedMapOf(
                "comedy" to EventType.COMEDY.name,
                "impro" to EventType.COMEDY.name,
                "open air" to EventType.PARTY.name,
                "openair" to EventType.PARTY.name,
                "club" to EventType.PARTY.name
            )

        val BILLING_SEPARATOR = Regex("""[+,&]""")

        val PLUS = Regex("""\s*\+\s*""")

        val TRACKING_PARAMETER = Regex("""[?&](?:fbclid|utm_[a-z]+)=[^&#]*""")

        val SOCIAL_PROFILE = Regex("""://(?:[\w-]+\.)?(?:instagram\.com|facebook\.com|fb\.me)/""", RegexOption.IGNORE_CASE)

        val UNKNOWN_COST = Regex("""(?i)\s*tba\s*""")

        val HOUSE_NAME = Regex("""\bKAOS\b""")
    }
}
