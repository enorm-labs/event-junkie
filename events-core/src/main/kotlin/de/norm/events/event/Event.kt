package de.norm.events.event

import de.norm.events.artist.Artist
import de.norm.events.promoter.Promoter
import de.norm.events.venue.Venue
import io.github.oshai.kotlinlogging.KotlinLogging
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

private val logger = KotlinLogging.logger {}

/**
 * Categorizes events by their type/kind as observed on venue websites.
 *
 * Derived from the "kind" field shown on sites like Astra Berlin
 * (Concert, Festival, Party, etc.) and category labels on Badehaus Berlin
 * (Concert, Party, Quiz, etc.).
 */
enum class EventType {
    CONCERT,
    FESTIVAL,
    PARTY,
    QUIZ,
    CLUB_NIGHT,
    SHOW,
    SCREENING,
    EXHIBITION,
    READING,
    OTHER;

    companion object {
        /** Safely parses [value] to an [EventType], falling back to [OTHER] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): EventType =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: OTHER.also { logger.warn { "Unknown EventType '$value', defaulting to OTHER" } }
    }
}

/**
 * Tracks the scheduling status of an event.
 *
 * Berlin venues frequently relocate ("VERLEGT") or cancel events;
 * this enum captures those states for display and filtering.
 */
enum class EventStatus {
    /** Event is taking place as originally planned. */
    SCHEDULED,

    /** Event has been moved to a different venue or date (German: "VERLEGT"). */
    RELOCATED,

    /** Event has been cancelled and will not take place. */
    CANCELLED,

    /** Event has been postponed to an unannounced future date. */
    POSTPONED;

    companion object {
        /** Safely parses [value] to an [EventStatus], falling back to [SCHEDULED] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): EventStatus =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: SCHEDULED.also { logger.warn { "Unknown EventStatus '$value', defaulting to SCHEDULED" } }
    }
}

/**
 * Core domain entity representing a music event at a venue.
 *
 * The [sourceId] field uniquely identifies this event from its import source,
 * enabling idempotent imports (upsert semantics). Format example: "astra:2026-06-12-the-adicts".
 */
data class Event(
    /** Database primary key, `null` before persistence. Example: `101` */
    val id: Long? = null,
    /** The venue where this event takes place. Example: Astra Kulturhaus */
    val venue: Venue,
    /** Artists performing at this event, with their roles and billing order. */
    val lineup: List<LineupEntry>,
    /** Promoters or presenters responsible for this event. Example: 36 Concerts */
    val promoters: List<Promoter>,
    /** Main headline or name of the event. Example: `"THE ADICTS"` */
    val title: String,
    /** Secondary line, often a tour name or support acts. Example: `"„Adios Amigos Tour 2026" + Support: MAID OF ACE + KAOS"` */
    val subtitle: String? = null,
    /** Longer description or artist biography. Example: `"Formed in Ipswich in the late 1970s…"` */
    val description: String? = null,
    /** Kind of event as categorized by the source venue. Example: [EventType.CONCERT] */
    val eventType: EventType = EventType.CONCERT,
    /** URL-friendly identifier, typically derived from date and title. Example: `"2026-06-12-the-adicts"` */
    val slug: String,
    /** Calendar date of the event. Example: `2026-06-12` */
    val eventDate: LocalDate,
    /** Time when doors open to the public. Example: `19:00` */
    val doorsTime: LocalTime? = null,
    /** Time when the show/performance starts. Example: `20:00` */
    val startTime: LocalTime? = null,
    /** URL of the event's poster or flyer image. Example: `"https://example.com/adicts-poster.jpg"` */
    val imageUrl: String? = null,
    /** Original URL on the source venue's website. Example: `"https://www.astra-berlin.de/events/2026-06-12-the-adicts"` */
    val sourceUrl: String? = null,
    /** Unique identifier from the import source for idempotent upserts. Example: `"astra:2026-06-12-the-adicts"` */
    val sourceId: String,
    /** URL to the external ticket shop (eventim, ticketshop.live, etc.). Example: `"https://www.eventim.de/event/..."` */
    val ticketUrl: String? = null,
    /** Direct link to the Facebook event page. Example: `"https://fb.me/e/60JFqXAUr"` */
    val facebookEventUrl: String? = null,
    /** Music genre or style tag as labelled by the source venue. Example: `"Punk"`, `"80s & 90s"` */
    val genre: String? = null,
    /** Scheduling status of the event (handles relocated/cancelled events). Example: [EventStatus.RELOCATED] */
    val status: EventStatus = EventStatus.SCHEDULED,
    /** Presale ticket price (Vorverkauf), `null` if not available. Example: `38.00` */
    val pricePresale: BigDecimal? = null,
    /** Box office ticket price (Abendkasse), `null` if not available. Example: `45.00` */
    val priceBoxOffice: BigDecimal? = null,
    /** ISO 4217 currency code for prices. Example: `"EUR"` */
    val priceCurrency: String = "EUR",
    /** Free-form pricing note for non-standard pricing (e.g. "donation 2-5€", "free entry"). */
    val priceNote: String? = null,
    /** Whether all tickets for this event are sold out. Example: `true` */
    val soldOut: Boolean = false,
    /** Whether this event is free to attend (no ticket needed / free entry). Example: `true` */
    val free: Boolean = false,
    /** Timestamp when this record was first created. Set by the database. */
    val createdAt: Instant? = null,
    /** Timestamp when this record was last modified. Set by the database. */
    val updatedAt: Instant? = null
)

/**
 * A single entry in an event's lineup, linking an [Artist] to an [Event]
 * with their role and billing position.
 *
 * Uses the full [Artist] object rather than a foreign key,
 * consistent with how [Event] references [Venue] and [Promoter].
 * The persistence layer (`EventArtistEntity`) handles the FK mapping.
 */
data class LineupEntry(
    val artist: Artist,
    /** The artist's role in the event lineup. Example: [ArtistRole.SUPPORT] */
    val role: ArtistRole = ArtistRole.HEADLINER,
    /** Position in the lineup, lower numbers appear first. Example: `0` for headliner, `1` for first support */
    val billingOrder: Int = 0,
    /** Room / stage the artist plays at this event (e.g. "Panorama Bar"). Null for single-room venues. */
    val stage: String? = null
)

/**
 * Describes an artist's role/position in an event lineup.
 */
enum class ArtistRole {
    /** Main act / top-billed performer */
    HEADLINER,

    /** Supporting act listed below the headliner */
    SUPPORT,

    /** DJ set, typically at aftershow parties or festivals */
    DJ;

    companion object {
        /** Safely parses [value] to an [ArtistRole], falling back to [HEADLINER] for unrecognized values. Case-insensitive, trims whitespace. */
        fun parseOrDefault(value: String): ArtistRole =
            entries.find { it.name.equals(value.trim(), ignoreCase = true) }
                ?: HEADLINER.also { logger.warn { "Unknown ArtistRole '$value', defaulting to HEADLINER" } }
    }
}
