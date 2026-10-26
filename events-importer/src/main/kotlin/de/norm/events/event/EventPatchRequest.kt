package de.norm.events.event

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.Valid
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Optional

/**
 * Body of `PATCH /api/admin/events/{id}`: only the fields sent change (#3995).
 *
 * A field every event has is a plain nullable, where absent or `null` keeps the stored value. A field an
 * operator may clear is an [Optional]: absent keeps, an explicit JSON `null` arrives as [Optional.empty]
 * and clears. `sourceId` is absent on purpose, because the importer matches events on it.
 */
@Schema(description = "The fields of an event to change. A field left out keeps its stored value")
data class EventPatchRequest(
    val venueId: Long? = null,
    val title: String? = null,
    @Schema(type = "string", nullable = true)
    val subtitle: Optional<String>? = null,
    @Schema(type = "string", nullable = true)
    val description: Optional<String>? = null,
    val eventType: EventType? = null,
    val status: EventStatus? = null,
    val eventDate: LocalDate? = null,
    @Schema(type = "string", format = "time", nullable = true)
    val doorsTime: Optional<LocalTime>? = null,
    @Schema(type = "string", format = "time", nullable = true)
    val startTime: Optional<LocalTime>? = null,
    @Schema(type = "string", nullable = true)
    val imageUrl: Optional<String>? = null,
    @Schema(type = "string", nullable = true)
    val sourceUrl: Optional<String>? = null,
    @Schema(type = "string", nullable = true)
    val ticketUrl: Optional<String>? = null,
    @Schema(type = "string", nullable = true)
    val facebookEventUrl: Optional<String>? = null,
    @Schema(type = "string", nullable = true)
    val genre: Optional<String>? = null,
    @Schema(type = "number", nullable = true)
    val pricePresale: Optional<BigDecimal>? = null,
    @Schema(type = "number", nullable = true)
    val priceBoxOffice: Optional<BigDecimal>? = null,
    val priceCurrency: String? = null,
    @Schema(type = "string", nullable = true)
    val priceNote: Optional<String>? = null,
    val soldOut: Boolean? = null,
    val free: Boolean? = null,
    @Schema(description = "Sent, it replaces the whole lineup")
    val artists: List<@Valid EventArtistRequest>? = null,
    @Schema(description = "Sent, it replaces every promoter")
    val promoterIds: List<Long>? = null,
    @Schema(type = "string", format = "date-time", nullable = true)
    val featuredUntil: Optional<Instant>? = null,
    @Schema(type = "integer", format = "int64", nullable = true)
    val festivalId: Optional<Long>? = null,
    @Schema(type = "string", nullable = true)
    val relocatedTo: Optional<String>? = null,
    @Schema(type = "string", format = "date", nullable = true)
    val relocatedOn: Optional<LocalDate>? = null
) {
    /**
     * The full PUT body this patch makes of [stored]. The stored lineup goes back with the four values
     * the importer compares, so an untouched lineup pins nothing. The pick, the festival and the move
     * pass through as sent, because [EventRequest] already reads a missing one as "keep".
     */
    fun mergedOnto(stored: EventResponse): EventRequest =
        EventRequest(
            venueId = venueId ?: stored.venueId,
            title = title ?: stored.title,
            subtitle = subtitle.or(stored.subtitle),
            description = description.or(stored.description),
            eventType = eventType ?: stored.eventType,
            status = status ?: stored.status,
            eventDate = eventDate ?: stored.eventDate,
            doorsTime = doorsTime.or(stored.doorsTime),
            startTime = startTime.or(stored.startTime),
            imageUrl = imageUrl.or(stored.imageUrl),
            sourceUrl = sourceUrl.or(stored.sourceUrl),
            sourceId = stored.sourceId,
            ticketUrl = ticketUrl.or(stored.ticketUrl),
            facebookEventUrl = facebookEventUrl.or(stored.facebookEventUrl),
            genre = genre.or(stored.genre),
            pricePresale = pricePresale.or(stored.pricePresale),
            priceBoxOffice = priceBoxOffice.or(stored.priceBoxOffice),
            priceCurrency = priceCurrency ?: stored.priceCurrency,
            priceNote = priceNote.or(stored.priceNote),
            soldOut = soldOut ?: stored.soldOut,
            free = free ?: stored.free,
            artists = artists ?: stored.artists.map { EventArtistRequest(it.artistId, it.role, it.billingOrder, it.stage) },
            promoterIds = promoterIds ?: stored.promoterIds,
            featuredUntil = featuredUntil,
            festivalId = festivalId,
            relocatedTo = relocatedTo,
            relocatedOn = relocatedOn
        )
}

/** The sent value, `null` for an explicit JSON `null`, or [stored] when the field was left out. */
private fun <T : Any> Optional<T>?.or(stored: T?): T? = if (this == null) stored else orElse(null)
