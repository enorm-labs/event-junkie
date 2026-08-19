package de.norm.events.event

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Request body for creating or updating an event.
 *
 * References venue, artists, and promoters by their database IDs.
 * Join-table entries (`event_artist`, `event_promoter`) are managed
 * automatically by the service layer. For updates, the event `id` is
 * taken from the path parameter.
 */
@Schema(description = "Request body for creating or updating an event")
data class EventRequest(
    @field:NotNull(message = "Venue ID must not be null")
    @Schema(description = "Database ID of the venue where this event takes place", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    val venueId: Long,
    @field:NotBlank(message = "Event title must not be blank")
    @field:Size(max = 255, message = "Event title must not exceed 255 characters")
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS", requiredMode = Schema.RequiredMode.REQUIRED)
    val title: String,
    @field:Size(max = 500, message = "Subtitle must not exceed 500 characters")
    @Schema(description = "Secondary line, often a tour name or support acts", example = "Adios Amigos Tour 2026 + Support: MAID OF ACE")
    val subtitle: String? = null,
    @field:Size(max = 10000, message = "Description must not exceed 10000 characters")
    @Schema(description = "Longer description or artist biography")
    val description: String? = null,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType = EventType.CONCERT,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus = EventStatus.SCHEDULED,
    @field:NotNull(message = "Event date must not be null")
    @Schema(description = "Calendar date of the event", example = "2026-06-12", requiredMode = Schema.RequiredMode.REQUIRED)
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime? = null,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime? = null,
    @field:Size(max = 2048, message = "Image URL must not exceed 2048 characters")
    @Schema(description = "URL of the event's poster or flyer image", example = "https://example.com/adicts-poster.jpg")
    val imageUrl: String? = null,
    @field:Size(max = 2048, message = "Source URL must not exceed 2048 characters")
    @Schema(description = "Original URL on the source venue's website", example = "https://www.astra-berlin.de/events/2026-06-12-the-adicts")
    val sourceUrl: String? = null,
    @field:NotBlank(message = "Source ID must not be blank")
    @field:Size(max = 255, message = "Source ID must not exceed 255 characters")
    @Schema(
        description = "Unique identifier from the import source for idempotent upserts",
        example = "astra:2026-06-12-the-adicts",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val sourceId: String,
    @field:Size(max = 2048, message = "Ticket URL must not exceed 2048 characters")
    @Schema(description = "URL to the external ticket shop", example = "https://www.eventim.de/event/...")
    val ticketUrl: String? = null,
    @field:Size(max = 2048, message = "Facebook event URL must not exceed 2048 characters")
    @Schema(description = "Direct link to the Facebook event page", example = "https://fb.me/e/60JFqXAUr")
    val facebookEventUrl: String? = null,
    @field:Size(max = 255, message = "Genre must not exceed 255 characters")
    @Schema(description = "Music genre or style tag", example = "Punk")
    val genre: String? = null,
    @Schema(description = "Presale ticket price (Vorverkauf)", example = "38.00")
    val pricePresale: BigDecimal? = null,
    @Schema(description = "Box office ticket price (Abendkasse)", example = "45.00")
    val priceBoxOffice: BigDecimal? = null,
    @field:Size(max = 10, message = "Currency code must not exceed 10 characters")
    @Schema(description = "ISO 4217 currency code for prices", example = "EUR")
    val priceCurrency: String = "EUR",
    @field:Size(max = 500, message = "Price note must not exceed 500 characters")
    @Schema(description = "Free-form pricing note for non-standard pricing", example = "donation 2-5€")
    val priceNote: String? = null,
    @Schema(description = "Whether all tickets for this event are sold out", example = "false")
    val soldOut: Boolean = false,
    @Schema(description = "Whether the event is free to attend (free entry)", example = "false")
    val free: Boolean = false,
    /** Artists performing at this event with their role and billing position. */
    @field:Valid
    @Schema(description = "Artists performing at this event with their role and billing position")
    val artists: List<EventArtistRequest> = emptyList(),
    @Schema(description = "Database IDs of promoters associated with this event", example = "[3, 7]")
    val promoterIds: List<Long> = emptyList()
)

/**
 * Describes an artist's participation in an event for create/update requests.
 */
@Schema(description = "An artist's participation in an event")
data class EventArtistRequest(
    @field:NotNull(message = "Artist ID must not be null")
    @Schema(description = "Database ID of the artist", example = "7", requiredMode = Schema.RequiredMode.REQUIRED)
    val artistId: Long,
    @Schema(description = "The artist's role in the event lineup", example = "HEADLINER")
    val role: ArtistRole = ArtistRole.HEADLINER,
    @Schema(description = "Position in the lineup — lower numbers appear first (0 = headliner)", example = "0")
    val billingOrder: Int = 0,
    @Schema(description = "Room / stage the artist plays (multi-room venues)", example = "Panorama Bar")
    val stage: String? = null
)
