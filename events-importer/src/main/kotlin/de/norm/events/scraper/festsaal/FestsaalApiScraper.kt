package de.norm.events.scraper.festsaal

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isFestivalTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSupportActs
import io.github.oshai.kotlinlogging.KotlinLogging
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeParseException

/** Admin host serving the Wagtail API and CMS-rendered pages; rewritten to the public host for user-facing URLs. */
private const val FESTSAAL_ADMIN_HOST = "admin.festsaal-kreuzberg.de"

/** Public host visitors browse; per-event [ScrapedEvent.sourceUrl] values point here, not at the admin CMS. */
private const val FESTSAAL_PUBLIC_HOST = "festsaal-kreuzberg.de"

/** Public listing base used to reconstruct an event URL when the API omits `meta.html_url`. */
private const val FESTSAAL_PROGRAMM_BASE = "https://$FESTSAAL_PUBLIC_HOST/de/programm/"

/** Length of an ISO `HH:mm` prefix, used to trim the API's `HH:mm:ss` clock strings before parsing. */
private const val HH_MM_LENGTH = 5

/**
 * Pure parser for Festsaal Kreuzberg's event data, sourced from its Wagtail
 * headless-CMS JSON REST API (`/api/v2/pages/?type=home.EventPage`).
 *
 * The public site is a Nuxt.js SPA that renders no event data server-side, so
 * scraping the HTML is impossible without a headless browser. The Wagtail API
 * behind it, however, exposes every upcoming event as clean structured JSON —
 * the most stable possible source (ADR-007 §"Selector Strategy" priority 1).
 * [FestsaalWebsiteImporter] fetches the response body; this class parses it.
 *
 * Each `items[]` entry carries `title`, `sub_title`, `date`, `doors`, `start`, `ticket`, `price`,
 * a nested `genre.title` and `preview_image.download_url`, a `support` act line, and a `status`
 * code (`sold_out`, `moved_date`, `transferred`, `custom`, or absent). Festsaal has no
 * event-category field, so the type is inferred from the title/subtitle like Bi Nuu (see
 * [inferEventType]).
 *
 * @see FestsaalWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://festsaal-kreuzberg.de/de/programm/">Festsaal Kreuzberg programme</a>
 */
class FestsaalApiScraper {
    private val logger = KotlinLogging.logger {}

    // Maps the API's snake_case fields onto camelCase DTO properties, so the DTOs need no
    // per-field @JsonProperty annotations. Unknown fields are ignored (Jackson 3 default).
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()

    /**
     * Parses every event from the Wagtail API listing response [json].
     *
     * @param json the raw JSON body of the `/api/v2/pages/?type=home.EventPage` response.
     * @return a list of [ScrapedEvent] instances, one per listed event; empty if the
     *   payload is absent, unparseable, or carries no `items`.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val items = parseItems(json) ?: return emptyList()
        logger.info { "Found ${items.size()} event(s) in Festsaal API response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        return items.mapNotNull { node ->
            try {
                parseEvent(jsonMapper.treeToValue(node, FestsaalEventNode::class.java))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Festsaal event, skipping" }
                null
            }
        }
    }

    /** Parses the response body and returns its `items` array, or null if the body is unparseable or has no array. */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
        "ReturnCount" // Guard clauses for the unparseable body and missing array are clearer than nesting.
    )
    private fun parseItems(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Festsaal Wagtail API response" }
                return null
            }
        val items = root.path("items")
        if (!items.isArray) {
            logger.warn { "Festsaal Wagtail API response has no 'items' array" }
            return null
        }
        return items
    }

    @Suppress("ReturnCount") // Guard clauses for the required slug, title, and date are clearer than nesting.
    private fun parseEvent(node: FestsaalEventNode): ScrapedEvent? {
        val slug = node.meta?.slug.blankToNull() ?: node.id?.toString()
        if (slug == null) {
            logger.warn { "Festsaal event has no slug or id, skipping" }
            return null
        }

        val title = node.title.blankToNull()
        if (title == null) {
            logger.warn { "Festsaal event '$slug' has no title, skipping" }
            return null
        }

        val statusCode = node.status.blankToNull()
        val postponed = statusCode == STATUS_MOVED_DATE

        // A postponed event moves to its `changed_*` date/times — the moment it now actually
        // happens — so those win when present; otherwise the original values stand.
        fun effective(
            changed: String?,
            base: String?
        ): String? = (if (postponed) changed.blankToNull() else null) ?: base.blankToNull()

        val eventDate = parseDate(effective(node.changedDate, node.date))
        if (eventDate == null) {
            logger.warn { "Festsaal event '$slug' has no parseable date, skipping" }
            return null
        }

        val doorsTime = parseClock(effective(node.changedDoors, node.doors))
        val startTime = parseClock(effective(node.changedStart, node.start))

        val subtitle = node.subTitle.blankToNull()
        val eventType = inferEventType(title, subtitle)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl =
                node.previewImage
                    ?.downloadUrl
                    .blankToNull()
                    ?.takeIf { it.startsWith("http") },
            sourceUrl = publicEventUrl(node.meta?.htmlUrl.blankToNull(), slug),
            sourceId = "${EventSource.FESTSAAL.sourceIdPrefix}$slug",
            ticketUrl = node.ticket.blankToNull()?.takeIf { it.startsWith("http") },
            genre = node.genre?.title.blankToNull(),
            pricePresale = parsePrice(node.price.blankToNull()),
            soldOut = statusCode == STATUS_SOLD_OUT,
            status = mapStatus(statusCode),
            artists = buildArtists(title, node.support.blankToNull(), eventType)
        )
    }

    /**
     * Maps Festsaal's `status` code to a domain [EventStatus][de.norm.events.event.EventStatus] name.
     *
     * Observed codes: `moved_date` (postponed to `changed_date`), `transferred` (relocated
     * to another venue), `sold_out` (captured separately as the `soldOut` flag, so it stays
     * `SCHEDULED` here) and `custom` (a free-text `changed_text` note we cannot classify —
     * treated as `SCHEDULED`). A `cancelled`-family code is mapped defensively even though
     * none appears in the current data; any other non-blank code is logged and defaults to
     * `SCHEDULED` so a newly-introduced code surfaces rather than being silently mismapped.
     */
    private fun mapStatus(code: String?): String =
        when (val normalized = code?.trim()?.lowercase()) {
            null, "", STATUS_SOLD_OUT, STATUS_CUSTOM -> {
                "SCHEDULED"
            }

            STATUS_MOVED_DATE -> {
                "POSTPONED"
            }

            STATUS_TRANSFERRED -> {
                "RELOCATED"
            }

            else -> {
                if (normalized.contains("cancel") || normalized.contains("abgesagt")) {
                    "CANCELLED"
                } else {
                    logger.warn { "Unknown Festsaal status code '$normalized', defaulting to SCHEDULED" }
                    "SCHEDULED"
                }
            }
        }

    /**
     * Infers the event type from the title/subtitle, since Festsaal exposes no
     * category field (`genre` is a *musical* genre, not an event kind).
     *
     * Festsaal is a live-music venue, so the default is `CONCERT`; only unambiguous
     * signals flip it — a quiz keyword → `QUIZ`, a wrestling show → `SHOW`, a market or
     * open-air event series → `OTHER`, a festival title → `FESTIVAL`, and a party/DJ-night
     * keyword (including a Brazilian "festa") → `PARTY`. Flipping away from `CONCERT` also
     * stops a non-artist title being minted as a headliner ([buildArtists]). Mirrors Bi
     * Nuu's `inferBinuuEventType`; like every curated heuristic it is reactive — a new
     * non-concert series reads as a `CONCERT` until a keyword catches it.
     *
     * The event-name markers (`wrestling` and [NON_CONCERT_EVENT_KEYWORDS]) are matched against
     * the **title only** — they identify an event whose *name* is the show/market/open-air, not an
     * act. The same marker in the *subtitle* is just a format note on a real concert (e.g. an act
     * billed "¡Wepa! Bunny" playing an open air), so it must not strip the headliner.
     */
    private fun inferEventType(
        title: String,
        subtitle: String?
    ): String {
        val titleLower = title.lowercase()
        val haystack = "$titleLower ${subtitle.orEmpty().lowercase()}"
        return when {
            "quiz" in haystack -> EventType.QUIZ.name
            "wrestling" in titleLower -> EventType.SHOW.name
            NON_CONCERT_EVENT_KEYWORDS.any { it in titleLower } -> EventType.OTHER.name
            isFestivalTitle(title) -> EventType.FESTIVAL.name
            PARTY_KEYWORDS.any { it in haystack } -> EventType.PARTY.name
            else -> EventType.CONCERT.name
        }
    }

    /**
     * Builds the lineup for an event: for concerts the title carries the headliner(s)
     * (co-bills split out via [headlinersFromTitle]) followed by the acts on the
     * structured `support` line; other types (parties, festivals, quizzes) name an event,
     * not an artist, so no artists are extracted. Support acts come from the API's dedicated
     * `support` field (the authoritative source), split on the usual separators via
     * [splitSupportActs]. Non-artist noise (placeholders, role labels, festival labels) is
     * filtered by the shared [isNonArtistName].
     */
    private fun buildArtists(
        title: String,
        support: String?,
        eventType: String
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()
        val supportActs =
            support
                ?.let(::splitSupportActs)
                .orEmpty()
                .filterNot(::isNonArtistName)
                .map { ScrapedArtist(name = it, role = "SUPPORT") }
        return headlinersFromTitle(title) + supportActs
    }

    /** Rewrites the CMS `html_url` from the admin host to the public host, or rebuilds it from the [slug]. */
    private fun publicEventUrl(
        htmlUrl: String?,
        slug: String
    ): String = htmlUrl?.replace(FESTSAAL_ADMIN_HOST, FESTSAAL_PUBLIC_HOST) ?: "$FESTSAAL_PROGRAMM_BASE$slug/"

    /** Parses an ISO `yyyy-MM-dd` date, returning null instead of throwing. */
    private fun parseDate(raw: String?): LocalDate? {
        if (raw.isNullOrBlank()) return null
        return try {
            LocalDate.parse(raw.trim())
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Parses the `HH:mm` prefix of the API's `HH:mm:ss` clock strings, returning null for missing/unparseable input. */
    private fun parseClock(raw: String?): LocalTime? = parseTime(raw?.trim()?.take(HH_MM_LENGTH)?.takeIf { it.isNotBlank() })

    /**
     * Parses Festsaal's plain decimal price string (e.g. `"51,80"`, German comma separator,
     * no currency symbol) into a positive [BigDecimal], or null when absent/unparseable.
     */
    private fun parsePrice(raw: String?): BigDecimal? {
        val cleaned = raw?.trim()?.replace(",", ".")?.takeIf { it.isNotBlank() } ?: return null
        return try {
            BigDecimal(cleaned).takeIf { it.signum() > 0 }
        } catch (_: NumberFormatException) {
            null
        }
    }

    private companion object {
        const val STATUS_SOLD_OUT = "sold_out"
        const val STATUS_MOVED_DATE = "moved_date"
        const val STATUS_TRANSFERRED = "transferred"
        const val STATUS_CUSTOM = "custom"

        /**
         * Party/DJ-night phrases that, in a title or subtitle, mark a non-concert night at this
         * concert-leaning venue. Includes the Brazilian `festa` (e.g. "Festa Junina"), a party.
         */
        val PARTY_KEYWORDS =
            listOf(
                "afterparty",
                "after-party",
                "after party",
                "party",
                "festa",
                "dj set",
                "dj-set",
                "rave",
                "karaoke",
                "club night",
                "clubnight"
            )

        /**
         * Title markers for non-music, non-party events whose **title** names the event, not an
         * artist: a market ("24. Japanmarkt") or an open-air event series ("Berlin Indie Open Air").
         * Matched against the title only (see [inferEventType]) so a subtitle "Open Air" format note
         * on a real concert doesn't strip its headliner. Mapped to `OTHER` so no headliner is minted.
         * Curated/reactive, like the venue's other heuristics.
         */
        val NON_CONCERT_EVENT_KEYWORDS = listOf("markt", "open air")
    }
}

/** Trims this string and returns `null` when it is null, empty, or all whitespace. */
private fun String?.blankToNull(): String? = this?.trim()?.takeIf { it.isNotBlank() }

/**
 * One event in the Wagtail listing (`items[]`), mapped from its JSON by Jackson.
 *
 * Only the fields Festsaal actually populates are declared; the mapper's
 * `SNAKE_CASE` strategy maps snake_case JSON keys (`sub_title`, `changed_date`,
 * `preview_image`) onto these camelCase properties, and unknown keys are ignored.
 * Every field is nullable/defaulted so a partial or evolving payload deserializes
 * cleanly and is validated in [FestsaalApiScraper.parseEvent] instead.
 */
private data class FestsaalEventNode(
    val id: Long? = null,
    val meta: FestsaalMeta? = null,
    val title: String? = null,
    val subTitle: String? = null,
    val status: String? = null,
    val date: String? = null,
    val changedDate: String? = null,
    val doors: String? = null,
    val changedDoors: String? = null,
    val start: String? = null,
    val changedStart: String? = null,
    val genre: FestsaalGenre? = null,
    val previewImage: FestsaalImage? = null,
    val ticket: String? = null,
    val price: String? = null,
    val support: String? = null
)

/** Wagtail page metadata: the URL [slug] and the CMS-rendered [htmlUrl] (on the admin host). */
private data class FestsaalMeta(
    val slug: String? = null,
    val htmlUrl: String? = null
)

/** The nested musical-genre object (`genre.title`), e.g. "Pop" — not an event category. */
private data class FestsaalGenre(
    val title: String? = null
)

/** The nested preview image; only its absolute [downloadUrl] is used. */
private data class FestsaalImage(
    val downloadUrl: String? = null
)
