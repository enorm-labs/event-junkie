package de.norm.events.scraper.madameclaude

import com.fasterxml.jackson.annotation.JsonProperty
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.detectFree
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isScreeningTitle
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.stripArtistSuffix
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for Madame Claude's event data from its WordPress REST API (`/wp-json/wp/v2/event`),
 * the venue's `event` custom-post-type endpoint.
 *
 * WordPress with an Advanced Custom Fields (ACF) `event` post type whose REST API exposes every
 * event as clean JSON — the most stable source possible (ADR-007 §"Selector Strategy" priority 1).
 * This replaced a two-page HTML scrape (events grid plus per-event detail pages): one API
 * request yields date, doors time, type, entrance fee, ticket link, genre, description and the
 * featured image (via `_embed`). [MadameClaudeWebsiteImporter] fetches the body; this class
 * parses it.
 *
 * Each entry carries the post `date` (the event's date **and** start time — stored identically
 * to `acf.event_date`), `title.rendered`, `slug`, a canonical `link`, an embedded
 * `wp:featuredmedia[0].source_url`, and an `acf` object with `event_type`, `event_doors_time`,
 * `event_entrance_fee`, `event_tickets_url`, `event_music_genre`, `event_card_subtitle`,
 * `event_description` and `event_status`. Participant fields are always empty, so artists come
 * from the title (as the old HTML scraper did). **No network I/O** — operates on the raw JSON
 * string (Jsoup only to unescape/flatten HTML in text fields), so it tests against a saved
 * API snapshot.
 *
 * @see MadameClaudeWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://madameclaude.de/events/">Madame Claude Events</a>
 */
class MadameClaudeApiScraper {
    private val logger = KotlinLogging.logger {}

    // Maps the API's snake_case fields onto camelCase DTO properties, so no per-field
    // @JsonProperty (except the two WP keys — `_embedded` and the colon-bearing `wp:featuredmedia`
    // — the SNAKE_CASE strategy cannot derive). Unknown fields are ignored (Jackson 3 default).
    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build()

    /**
     * Parses every event from the WP REST API listing response [json].
     *
     * @param json the raw JSON body of the `/wp-json/wp/v2/event` response (a JSON array).
     * @return one [ScrapedEvent] per listed event; empty if absent, unparseable or not an array.
     */
    fun scrape(json: String): List<ScrapedEvent> {
        val root = parseRoot(json) ?: return emptyList()
        logger.info { "Found ${root.size()} event(s) in Madame Claude API response" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed events without aborting the import.
        return root.mapNotNull { node ->
            try {
                parseEvent(jsonMapper.treeToValue(node, MadameClaudeEventNode::class.java))
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Madame Claude event, skipping" }
                null
            }
        }
    }

    /** The response body as a JSON array, or null if unparseable or not an array. */
    @Suppress(
        "TooGenericExceptionCaught", // A malformed payload must degrade to null, never abort the import.
        "ReturnCount" // Guard clauses for the unparseable body and non-array root are clearer than nesting.
    )
    private fun parseRoot(json: String): JsonNode? {
        val root =
            try {
                jsonMapper.readTree(json)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Madame Claude WP REST API response" }
                return null
            }
        if (!root.isArray) {
            logger.warn { "Madame Claude WP REST API response is not a JSON array" }
            return null
        }
        return root
    }

    @Suppress("ReturnCount") // Guard clauses for the required slug, title, and date are clearer than nesting.
    private fun parseEvent(node: MadameClaudeEventNode): ScrapedEvent? {
        val acf = node.acf

        val slug = node.slug.blankToNull() ?: node.id?.toString()
        if (slug == null) {
            logger.warn { "Madame Claude event has no slug or id, skipping" }
            return null
        }

        val title =
            node.title
                ?.rendered
                .blankToNull()
                ?.let { Parser.unescapeEntities(it, false) }
                // The editor leaves a stray double space in some titles ("Adventurous Juan  (DJ-Set)"); the
                // shared cleanup collapses whitespace runs and strips the trailing notes every other scraper
                // routes its title through.
                ?.let(::cleanEventTitle)
                .blankToNull()
        if (title == null) {
            logger.warn { "Madame Claude event '$slug' has no title, skipping" }
            return null
        }

        val postDate = node.date.blankToNull()
        val eventDate = parseEventDate(postDate, acf?.eventDate.blankToNull())
        if (eventDate == null) {
            logger.warn { "Madame Claude event '$slug' has no parseable date, skipping" }
            return null
        }

        val eventType = inferEventType(acf?.eventType.blankToNull(), title)

        // Post date carries the start time; a 00:00 value is the CMS's "unset" sentinel, not midnight.
        val startTime = parseClockPrefix(postDate?.substringAfter('T', "")).takeIf { it != LocalTime.MIDNIGHT }
        val doorsTime = parseClockPrefix(acf?.eventDoorsTime.blankToNull())

        val entranceFee = acf?.eventEntranceFee.blankToNull()
        val free = detectFree(priceNote = entranceFee, title = title)

        return ScrapedEvent(
            title = title,
            subtitle = acf?.eventCardSubtitle.blankToNull(),
            description = htmlToText(acf?.eventDescription),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = doorsTime,
            startTime = startTime,
            imageUrl = node.embeddedImageUrl(),
            sourceUrl = node.link.blankToNull() ?: "https://madameclaude.de/event/$slug/",
            sourceId = "${EventSource.MADAME_CLAUDE.sourceIdPrefix}$slug",
            ticketUrl = acf?.eventTicketsUrl.blankToNull()?.takeIf { it.startsWith("http") },
            genre = acf?.eventMusicGenre.blankToNull(),
            pricePresale = parsePriceValue(acf?.eventPrice.blankToNull()),
            // ACF exposes no box-office price; entrance fee is a human label kept as a note.
            priceNote = if (free) null else entranceFee,
            free = free,
            status = mapStatus(acf?.eventStatus.blankToNull()),
            artists = buildArtists(title, eventType)
        )
    }

    /**
     * Types the event from the ACF `event_type` label (the authoritative CMS value), falling back
     * to a title-based screening net when the label is unknown — a "SCREENING" title ("SHORTIES
     * FILMS SCREENING #28") should not fall to `OTHER`. `null` when nothing matches so the
     * persistence boundary applies the `OTHER` default.
     */
    private fun inferEventType(
        typeLabel: String?,
        title: String
    ): String? = mapEventType(typeLabel, MADAME_CLAUDE_TYPE_SYNONYMS) ?: if (isScreeningTitle(title)) EventType.SCREENING.name else null

    /**
     * The lineup from the title, keyed off the authoritative [eventType]:
     * - **Concerts** — co-billed acts (`A + B + C`) split into headliners via [headlinersFromTitle]
     * with `splitOnSlash = false` (the venue uses `/` *inside* a single act name — `Morimoto / Wong
     * duo` — so co-bills are delimited only by `+`); a trailing "(DJ-Set)" on the last act is
     * stripped as an artist-name suffix.
     * - **Parties** — only a "(DJ-Set)" night names its DJs ([djSetArtistsFromTitle], role `DJ`);
     * a party titled as an event ("Summer Break Send-Off") mints none.
     * - **Everything else** (quiz, screening, festival) — the title names an event, no artists.
     */
    private fun buildArtists(
        title: String,
        eventType: String?
    ): List<ScrapedArtist> =
        when (eventType) {
            EventType.CONCERT.name -> headlinersFromTitle(title, splitOnSlash = false)
            EventType.PARTY.name -> if (isDjSetTitle(title)) djSetArtistsFromTitle(title) else emptyList()
            else -> emptyList()
        }

    /**
     * Maps the ACF `event_status` to a domain [EventStatus][de.norm.events.event.EventStatus] name.
     * Every current event is `Scheduled`; cancelled/postponed/relocated codes are mapped
     * defensively so a future status surfaces rather than passing as scheduled.
     */
    private fun mapStatus(code: String?): String =
        when (val normalized = code?.trim()?.lowercase()) {
            null, "", "scheduled" -> {
                "SCHEDULED"
            }

            else -> {
                when {
                    normalized.contains("cancel") || normalized.contains("abgesagt") -> {
                        "CANCELLED"
                    }

                    normalized.contains("postpon") || normalized.contains("verschoben") -> {
                        "POSTPONED"
                    }

                    normalized.contains("reloc") || normalized.contains("verlegt") -> {
                        "RELOCATED"
                    }

                    else -> {
                        logger.warn { "Unknown Madame Claude status '$normalized', defaulting to SCHEDULED" }
                        "SCHEDULED"
                    }
                }
            }
        }

    /** The embedded featured-media URL (`_embedded.wp:featuredmedia[0].source_url`), or null. */
    private fun MadameClaudeEventNode.embeddedImageUrl(): String? =
        embedded
            ?.featuredMedia
            ?.firstOrNull()
            ?.sourceUrl
            .blankToNull()
            ?.takeIf { it.startsWith("http") }

    /**
     * The event date from the post `date` (ISO `yyyy-MM-dd'T'HH:mm:ss`), else the ACF `event_date`
     * (space-separated `yyyy-MM-dd HH:mm:ss`). Both encode the same day.
     */
    private fun parseEventDate(
        postDate: String?,
        acfDate: String?
    ): LocalDate? =
        postDate?.let { parseIsoDate(it) }
            ?: acfDate?.substringBefore(' ')?.let { parseIsoDate(it) }

    /**
     * Flattens a WordPress HTML blob (`event_description`) to plain text: Jsoup strips tags while
     * the source's line breaks stay, `&nbsp;` becomes a space, lines are trimmed, blank-line runs
     * collapse. Null when absent or empty.
     */
    private fun htmlToText(html: String?): String? {
        if (html.isNullOrBlank()) return null
        return Jsoup
            .parseBodyFragment(html)
            .wholeText()
            .replace('\u00A0', ' ') // normalise &nbsp; to a regular space
            .lines()
            .joinToString("\n") { it.trim() }
            .replace(BLANK_LINE_RUN, "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private companion object {
        /** Collapses three-or-more consecutive newlines down to a single blank line. */
        val BLANK_LINE_RUN = Regex("\n{3,}")

        /**
         * ACF `event_type` labels mapped to [EventType]. `Live` and `Open Mic` are live-music formats
         * (→ `CONCERT`); `DJ`, `Party` and `Karaoke` are club nights (→ `PARTY`); `Film Night` is a `SCREENING`.
         */
        val MADAME_CLAUDE_TYPE_SYNONYMS: Map<String, String> =
            mapOf(
                "concert" to EventType.CONCERT.name,
                "live" to EventType.CONCERT.name,
                "open mic" to EventType.CONCERT.name,
                "music quiz" to EventType.QUIZ.name,
                "dj" to EventType.PARTY.name,
                "party" to EventType.PARTY.name,
                "karaoke" to EventType.PARTY.name,
                "film night" to EventType.SCREENING.name,
                "festival" to EventType.FESTIVAL.name
            )
    }
}

/**
 * One event in the WP REST API listing, mapped by Jackson. Only the fields the venue populates
 * are declared; `SNAKE_CASE` maps `event_doors_time`, `event_card_subtitle` etc. onto these
 * camelCase properties, unknown keys are ignored. Date **and** start time live in the
 * top-level [date]; the ACF block carries the rest. Every field is nullable/defaulted so a
 * partial or evolving payload deserializes and is validated in
 * [MadameClaudeApiScraper.parseEvent] instead.
 */
private data class MadameClaudeEventNode(
    val id: Long? = null,
    val slug: String? = null,
    val date: String? = null,
    val link: String? = null,
    val title: MadameClaudeTitle? = null,
    val acf: MadameClaudeAcf? = null,
    // WordPress's `_embedded` key (leading underscore) is not derivable from the naming strategy.
    @param:JsonProperty("_embedded") val embedded: MadameClaudeEmbedded? = null
)

/** The WordPress `title` object; only its HTML-`rendered` form is used. */
private data class MadameClaudeTitle(
    val rendered: String? = null
)

/** The Advanced Custom Fields (`acf`) block carrying every non-core event attribute. */
private data class MadameClaudeAcf(
    val eventDate: String? = null,
    val eventDoorsTime: String? = null,
    val eventStatus: String? = null,
    val eventType: String? = null,
    val eventEntranceFee: String? = null,
    val eventPrice: String? = null,
    val eventTicketsUrl: String? = null,
    val eventMusicGenre: String? = null,
    val eventCardSubtitle: String? = null,
    val eventDescription: String? = null
)

/** The `_embedded` expansion block; only the featured-media list is used. */
private data class MadameClaudeEmbedded(
    // The `wp:featuredmedia` key carries a colon the naming strategy cannot derive.
    @param:JsonProperty("wp:featuredmedia") val featuredMedia: List<MadameClaudeMedia>? = null
)

/** A single embedded media item; only its absolute [sourceUrl] is used. */
private data class MadameClaudeMedia(
    val sourceUrl: String? = null
)

/** The "(DJ-Set)" / "(DJ Set)" marker appended to a DJ-night title. */
private val DJ_SET_TITLE_MARKER = Regex("""\(\s*dj[\s-]?set\s*\)""", RegexOption.IGNORE_CASE)

/** "+" separator between co-billed DJs (a "/" belongs to a single duo name). */
private val DJ_ACT_SEPARATOR = Regex("""\s*\+\s*""")

/**
 * Whether [title] carries the "(DJ-Set)" marker — a party whose lineup comes from the title
 * (the DJ names) rather than a title that is an event name with no performers.
 */
private fun isDjSetTitle(title: String): Boolean = DJ_SET_TITLE_MARKER.containsMatchIn(title)

/**
 * The DJ lineup from a "(DJ-Set)" [title]. Co-DJs are billed with `+` and `/` sits **inside**
 * a single act name (`Morimoto / Wong duo`), so the suffix is stripped, acts split on `+` then
 * on guarded `&`/`and`/`und` ([splitSegmentOnConjunctions]) — never `/` — per-act tour/format
 * suffixes stripped, non-artists (placeholders, `Open Mic L. J. Fox`, `DJ-Set / Berlin`)
 * dropped. All role `DJ`, in billing order.
 */
internal fun djSetArtistsFromTitle(title: String): List<ScrapedArtist> =
    stripArtistSuffix(title)
        .split(DJ_ACT_SEPARATOR)
        .flatMap { splitSegmentOnConjunctions(it) }
        .map { stripArtistSuffix(it.trim()) }
        .filterNot { it.isBlank() || isNonArtistName(it) }
        .distinct()
        .map { ScrapedArtist(name = it, role = "DJ") }
