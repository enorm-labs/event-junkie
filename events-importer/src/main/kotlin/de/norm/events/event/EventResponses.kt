package de.norm.events.event

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Response DTO for an event, including resolved artist and promoter associations.
 *
 * Uses IDs for related entities to keep the response flat and avoid
 * circular references. Clients can resolve full objects via separate endpoints.
 */
@Schema(description = "Response DTO for an event with resolved associations")
data class EventResponse(
    @Schema(description = "Database primary key", example = "101")
    val id: Long,
    @Schema(description = "Database ID of the venue where this event takes place", example = "42")
    val venueId: Long,
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS")
    val title: String,
    @Schema(description = "Secondary line, often a tour name or support acts", example = "Adios Amigos Tour 2026 + Support: MAID OF ACE")
    val subtitle: String?,
    @Schema(description = "Longer description or artist biography")
    val description: String?,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus,
    @Schema(description = "URL-friendly identifier", example = "2026-06-12-the-adicts")
    val slug: String,
    @Schema(description = "Calendar date of the event", example = "2026-06-12")
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime?,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime?,
    @Schema(description = "URL of the event's poster or flyer image", example = "https://example.com/adicts-poster.jpg")
    val imageUrl: String?,
    @Schema(description = "Original URL on the source venue's website")
    val sourceUrl: String?,
    @Schema(description = "Unique identifier from the import source", example = "astra:2026-06-12-the-adicts")
    val sourceId: String,
    @Schema(description = "URL to the external ticket shop")
    val ticketUrl: String?,
    @Schema(description = "Direct link to the Facebook event page")
    val facebookEventUrl: String?,
    @Schema(description = "Music genre or style tag (raw text from source)", example = "Punk")
    val genre: String?,
    @Schema(description = "Normalized genre tags for filtering", example = "[\"Punk\"]")
    val genreTags: List<String>,
    @Schema(description = "Presale ticket price (Vorverkauf)", example = "38.00")
    val pricePresale: BigDecimal?,
    @Schema(description = "Box office ticket price (Abendkasse)", example = "45.00")
    val priceBoxOffice: BigDecimal?,
    @Schema(description = "ISO 4217 currency code for prices", example = "EUR")
    val priceCurrency: String,
    @Schema(description = "Free-form pricing note for non-standard pricing")
    val priceNote: String?,
    @Schema(description = "Whether all tickets for this event are sold out", example = "false")
    val soldOut: Boolean,
    @Schema(description = "Whether the event is free to attend (free entry)", example = "false")
    val free: Boolean,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?,
    @Schema(description = "Artists performing at this event with their role and billing position")
    val artists: List<EventArtistResponse>,
    @Schema(description = "Database IDs of promoters associated with this event", example = "[3, 7]")
    val promoterIds: List<Long>
) {
    companion object {
        /**
         * Converts an [EventEntity] plus its resolved associations to an API response.
         *
         * Named `fromEntity` (rather than `fromDomain` like other response DTOs) because
         * the domain [Event] holds full [Artist]/[Promoter] objects whereas the API response
         * uses flat IDs. Building from the entity + pre-resolved association lists avoids
         * an unnecessary domain round-trip and keeps the mapping straightforward.
         */
        fun fromEntity(
            entity: EventEntity,
            artists: List<EventArtistResponse>,
            promoterIds: List<Long>,
            genreTagNames: List<String> = emptyList()
        ): EventResponse =
            EventResponse(
                id = requireNotNull(entity.id) { "Event must be persisted before converting to response" },
                venueId = entity.venueId,
                title = entity.title,
                subtitle = entity.subtitle,
                description = entity.description,
                eventType = EventType.valueOf(entity.eventType),
                status = EventStatus.valueOf(entity.status),
                slug = entity.slug,
                eventDate = entity.eventDate,
                doorsTime = entity.doorsTime,
                startTime = entity.startTime,
                imageUrl = entity.imageUrl,
                sourceUrl = entity.sourceUrl,
                sourceId = entity.sourceId,
                ticketUrl = entity.ticketUrl,
                facebookEventUrl = entity.facebookEventUrl,
                genre = entity.genre,
                genreTags = genreTagNames,
                pricePresale = entity.pricePresale,
                priceBoxOffice = entity.priceBoxOffice,
                priceCurrency = entity.priceCurrency,
                priceNote = entity.priceNote,
                soldOut = entity.soldOut,
                free = entity.free,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                artists = artists,
                promoterIds = promoterIds
            )
    }
}

/**
 * Response DTO for an artist's participation in an event.
 */
@Schema(description = "An artist's participation in an event")
data class EventArtistResponse(
    @Schema(description = "Database ID of the artist", example = "7")
    val artistId: Long,
    @Schema(description = "The artist's role in the event lineup", example = "HEADLINER")
    val role: ArtistRole,
    @Schema(description = "Position in the lineup — lower numbers appear first", example = "0")
    val billingOrder: Int,
    @Schema(description = "Room / stage the artist plays (multi-room venues), or null", example = "Panorama Bar")
    val stage: String? = null,
    @Schema(description = "When the set starts, from the venue's running order, or null", example = "2026-09-26T21:59:00Z")
    val setStart: Instant? = null,
    @Schema(description = "When the set ends, or null", example = "2026-09-27T02:30:00Z")
    val setEnd: Instant? = null
) {
    companion object {
        fun fromEntity(entity: EventArtistEntity): EventArtistResponse =
            EventArtistResponse(
                artistId = entity.artistId,
                role = ArtistRole.valueOf(entity.role),
                billingOrder = entity.billingOrder,
                stage = entity.stage,
                setStart = entity.setStart,
                setEnd = entity.setEnd
            )
    }
}

/**
 * What one replay of description-language detection classified.
 *
 * `unknown` is a result, not a failure: a line-up list or a field holding both languages is
 * deliberately left unclassified (ADR-026).
 */
@Schema(description = "Counts from one replay of description-language detection")
data class DescriptionLanguageBackfill(
    @Schema(description = "Descriptions classified as German", example = "1352")
    val german: Int,
    @Schema(description = "Descriptions classified as English", example = "520")
    val english: Int,
    @Schema(description = "Descriptions the classifier could not call, left unclassified", example = "275")
    val unknown: Int
)
