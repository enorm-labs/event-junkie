package de.norm.events.event

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Read-only R2DBC entity mapped to the `event` table.
 *
 * Lean projection for the BFF's read paths — the table is owned and written by the importer.
 * Artist, promoter, and genre tag associations are loaded via the join-table entities below.
 */
@Table("event")
data class EventEntity(
    @Id val id: Long? = null,
    val venueId: Long,
    /**
     * The source that produced this event, or `null` where that source has since been deleted
     * (`ON DELETE SET NULL`). Read only to resolve the per-source licence gate (#283).
     */
    val eventSourceId: Long? = null,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    val eventType: String = "CONCERT",
    val status: String = "SCHEDULED",
    val slug: String,
    val eventDate: LocalDate,
    val doorsTime: LocalTime? = null,
    val startTime: LocalTime? = null,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val ticketUrl: String? = null,
    val facebookEventUrl: String? = null,
    val genre: String? = null,
    val pricePresale: BigDecimal? = null,
    val priceBoxOffice: BigDecimal? = null,
    val priceCurrency: String = "EUR",
    val priceNote: String? = null,
    val soldOut: Boolean = false,
    val free: Boolean = false,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
)

/**
 * Read-only R2DBC entity mapped to the `event_artist` join table.
 */
@Table("event_artist")
data class EventArtistEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val artistId: Long,
    val role: String = "HEADLINER",
    val billingOrder: Int = 0,
    /** Room / stage the artist plays at this event (e.g. "Panorama Bar"). Null for single-room venues. */
    val stage: String? = null
)

/**
 * Read-only R2DBC entity mapped to the `event_promoter` join table.
 */
@Table("event_promoter")
data class EventPromoterEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val promoterId: Long
)
