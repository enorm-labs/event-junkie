package de.norm.events.event

import de.norm.events.artist.ArtistSummaryResponse
import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import de.norm.events.promoter.PromoterSummaryResponse
import de.norm.events.venue.VenueSummaryResponse
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/**
 * Compact event representation for list, calendar, and "today" responses.
 *
 * Embeds a venue summary and flat artist-name/genre lists so the frontend can render event
 * cards without follow-up requests.
 */
@Schema(description = "Compact event summary for lists and calendar")
data class EventSummaryResponse(
    @Schema(description = "Database primary key", example = "101")
    val id: Long,
    @Schema(description = "URL-friendly identifier (format: {date}-{venue}-{title})", example = "2026-06-18-lido-sam-prekop-john-mcentire")
    val slug: String,
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS")
    val title: String,
    @Schema(description = "Secondary line, often a tour name or support acts")
    val subtitle: String?,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus,
    @Schema(description = "Calendar date of the event", example = "2026-06-12")
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime?,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime?,
    @Schema(
        description =
            "Where the poster is fetched from. A path on this origin once the environment serves cached images, " +
                "and the venue's own URL until then (ADR-019)."
    )
    val imageUrl: String?,
    @Schema(
        description =
            "Better formats of the same poster, best first, for a <picture> element. Empty when the image is not " +
                "cached, in which case `imageUrl` is all there is."
    )
    val imageSources: List<ImageSourceResponse>,
    @Schema(
        description =
            "Pixel width of the original image, for the `width` attribute. Null together with " +
                "`intrinsicHeight` when the dimensions are unknown.",
        example = "1200"
    )
    val intrinsicWidth: Int?,
    @Schema(description = "Pixel height of the original image, for the `height` attribute", example = "630")
    val intrinsicHeight: Int?,
    @Schema(
        description =
            "True when a licence prohibition removed the image. False both when the venue " +
                "published none and when one is shown — a placeholder renders either way.",
        example = "false"
    )
    val imageWithheld: Boolean,
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
    @Schema(description = "The venue where this event takes place")
    val venue: VenueSummaryResponse,
    @Schema(description = "Artist names in billing order (headliner first)", example = "[\"The Adicts\", \"Maid Of Ace\"]")
    val artistNames: List<String>,
    @Schema(description = "Normalized genre tags", example = "[\"Punk\"]")
    val genreTags: List<String>
) {
    companion object {
        fun fromEntity(
            entity: EventEntity,
            venue: VenueSummaryResponse,
            artistNames: List<String>,
            genreTags: List<String>,
            image: ServedImage,
            imageWithheld: Boolean
        ): EventSummaryResponse =
            EventSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted event must have an ID" },
                slug = entity.slug,
                title = entity.title,
                subtitle = entity.subtitle,
                eventType = EventType.parseOrDefault(entity.eventType),
                status = EventStatus.parseOrDefault(entity.status),
                eventDate = entity.eventDate,
                doorsTime = entity.doorsTime,
                startTime = entity.startTime,
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                imageWithheld = imageWithheld,
                pricePresale = entity.pricePresale,
                priceBoxOffice = entity.priceBoxOffice,
                priceCurrency = entity.priceCurrency,
                priceNote = entity.priceNote,
                soldOut = entity.soldOut,
                free = entity.free,
                venue = venue,
                artistNames = artistNames,
                genreTags = genreTags
            )
    }
}

/**
 * Full event representation for the event detail page, with embedded venue, ordered lineup,
 * promoters, and genre tags.
 */
@Schema(description = "Full event detail with embedded associations")
data class EventDetailResponse(
    @Schema(description = "Database primary key", example = "101")
    val id: Long,
    @Schema(description = "URL-friendly identifier (format: {date}-{venue}-{title})", example = "2026-06-18-lido-sam-prekop-john-mcentire")
    val slug: String,
    @Schema(description = "Main headline or name of the event", example = "THE ADICTS")
    val title: String,
    @Schema(description = "Secondary line, often a tour name or support acts")
    val subtitle: String?,
    @Schema(description = "Longer description or artist biography")
    val description: String?,
    @Schema(description = "Kind of event", example = "CONCERT")
    val eventType: EventType,
    @Schema(description = "Scheduling status of the event", example = "SCHEDULED")
    val status: EventStatus,
    @Schema(description = "Calendar date of the event", example = "2026-06-12")
    val eventDate: LocalDate,
    @Schema(description = "Time when doors open to the public", example = "19:00")
    val doorsTime: LocalTime?,
    @Schema(description = "Time when the show/performance starts", example = "20:00")
    val startTime: LocalTime?,
    @Schema(
        description =
            "Where the poster is fetched from. A path on this origin once the environment serves cached images, " +
                "and the venue's own URL until then (ADR-019)."
    )
    val imageUrl: String?,
    @Schema(
        description =
            "Better formats of the same poster, best first, for a <picture> element. Empty when the image is not " +
                "cached, in which case `imageUrl` is all there is."
    )
    val imageSources: List<ImageSourceResponse>,
    @Schema(
        description =
            "Pixel width of the original image, for the `width` attribute. Null together with " +
                "`intrinsicHeight` when the dimensions are unknown.",
        example = "1200"
    )
    val intrinsicWidth: Int?,
    @Schema(description = "Pixel height of the original image, for the `height` attribute", example = "630")
    val intrinsicHeight: Int?,
    @Schema(
        description =
            "True when a licence prohibition removed the image. False both when the venue " +
                "published none and when one is shown — a placeholder renders either way.",
        example = "false"
    )
    val imageWithheld: Boolean,
    @Schema(
        description =
            "True when a licence prohibition removed the description. False when the venue " +
                "simply wrote none, which is far more common and needs no explanation.",
        example = "false"
    )
    val descriptionWithheld: Boolean,
    @Schema(description = "Original URL on the source venue's website")
    val sourceUrl: String?,
    @Schema(description = "URL to the external ticket shop")
    val ticketUrl: String?,
    @Schema(description = "Direct link to the Facebook event page")
    val facebookEventUrl: String?,
    @Schema(description = "Music genre or style tag (raw text from source)", example = "Punk")
    val genre: String?,
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
    @Schema(description = "The venue where this event takes place")
    val venue: VenueSummaryResponse,
    @Schema(description = "Lineup in billing order (headliner first)")
    val lineup: List<LineupEntryResponse>,
    @Schema(description = "Promoters or presenters responsible for this event")
    val promoters: List<PromoterSummaryResponse>,
    @Schema(description = "Normalized genre tags", example = "[\"Punk\"]")
    val genreTags: List<String>
) {
    companion object {
        fun fromEntity(
            entity: EventEntity,
            venue: VenueSummaryResponse,
            lineup: List<LineupEntryResponse>,
            promoters: List<PromoterSummaryResponse>,
            genreTags: List<String>,
            image: ServedImage,
            imageWithheld: Boolean,
            descriptionWithheld: Boolean
        ): EventDetailResponse =
            EventDetailResponse(
                id = requireNotNull(entity.id) { "Persisted event must have an ID" },
                slug = entity.slug,
                title = entity.title,
                subtitle = entity.subtitle,
                description = entity.description,
                eventType = EventType.parseOrDefault(entity.eventType),
                status = EventStatus.parseOrDefault(entity.status),
                eventDate = entity.eventDate,
                doorsTime = entity.doorsTime,
                startTime = entity.startTime,
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                imageWithheld = imageWithheld,
                descriptionWithheld = descriptionWithheld,
                sourceUrl = entity.sourceUrl,
                ticketUrl = entity.ticketUrl,
                facebookEventUrl = entity.facebookEventUrl,
                genre = entity.genre,
                pricePresale = entity.pricePresale,
                priceBoxOffice = entity.priceBoxOffice,
                priceCurrency = entity.priceCurrency,
                priceNote = entity.priceNote,
                soldOut = entity.soldOut,
                free = entity.free,
                venue = venue,
                lineup = lineup,
                promoters = promoters,
                genreTags = genreTags
            )
    }
}

/**
 * A single entry in an event's lineup: the artist plus their role and billing position.
 */
@Schema(description = "An artist's participation in an event lineup")
data class LineupEntryResponse(
    @Schema(description = "The performing artist")
    val artist: ArtistSummaryResponse,
    @Schema(description = "The artist's role in the lineup", example = "HEADLINER")
    val role: ArtistRole,
    @Schema(description = "Position in the lineup — lower numbers appear first", example = "0")
    val billingOrder: Int,
    @Schema(description = "Room / stage the artist plays (multi-room venues), or null", example = "Panorama Bar")
    val stage: String? = null
)
