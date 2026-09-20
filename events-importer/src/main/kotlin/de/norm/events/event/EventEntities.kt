package de.norm.events.event

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * R2DBC entity mapped to the `event` table.
 *
 * Stores only the event's own columns (including `venue_id` and optional
 * `event_source_id` as FKs). Artist and promoter associations are managed
 * through separate join-table entities.
 */
@Table("event")
data class EventEntity(
    @Id val id: Long? = null,
    val venueId: Long,
    /** FK to [de.norm.events.scraper.EventSourceEntity] that imported this event, or null for manually created events. */
    val eventSourceId: Long? = null,
    val title: String,
    val subtitle: String? = null,
    val description: String? = null,
    /** `de` or `en`, detected from [description] at import. Null when the text is too short or mixes both (ADR-026). */
    val descriptionLanguage: String? = null,
    val descriptionLanguageConfidence: BigDecimal? = null,
    /** The description in the other locale, when the publisher wrote one or a grant allowed a translation. */
    val descriptionAlt: String? = null,
    val descriptionAltLanguage: String? = null,
    /** `PUBLISHER` or `MACHINE`. A machine translation is labelled on the page and is never the record. */
    val descriptionAltOrigin: String? = null,
    val descriptionAltEngine: String? = null,
    /** SHA-256 of the [description] the alt text was made from. A changed original invalidates it. */
    val descriptionAltSourceHash: String? = null,
    val eventType: String = EventType.CONCERT.name,
    val status: String = EventStatus.SCHEDULED.name,
    /** Where a `RELOCATED` event moved to, as the venue's note names it; null when the note names nothing (#1551). */
    val relocatedTo: String? = null,
    val slug: String,
    val eventDate: LocalDate,
    val doorsTime: LocalTime? = null,
    val startTime: LocalTime? = null,
    /** The end, only when the venue states one (ADR-029). `endTime` never without `endDate`. */
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
    val imageUrl: String? = null,
    val sourceUrl: String? = null,
    val sourceId: String,
    val ticketUrl: String? = null,
    val facebookEventUrl: String? = null,
    val genre: String? = null,
    val pricePresale: BigDecimal? = null,
    val priceBoxOffice: BigDecimal? = null,
    val priceCurrency: String = "EUR",
    val priceNote: String? = null,
    val soldOut: Boolean = false,
    val free: Boolean = false,
    @CreatedDate val createdAt: Instant? = null,
    @LastModifiedDate val updatedAt: Instant? = null
)

/**
 * R2DBC entity mapped to the `event_artist` join table.
 *
 * Uses a surrogate auto-generated `id` column so that Spring Data R2DBC
 * correctly detects new entities (id = null → INSERT). A unique constraint
 * on (event_id, artist_id) preserves the logical composite key.
 */
@Table("event_artist")
data class EventArtistEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val artistId: Long,
    val role: String = ArtistRole.HEADLINER.name,
    val billingOrder: Int = 0,
    /** Room / stage the artist plays at this event (e.g. "Panorama Bar"). Null for single-room venues. */
    val stage: String? = null,
    /** The name was read off the event title (#1145); see `ScrapedArtist.titleDerived`. */
    val titleDerived: Boolean = false
)

/**
 * R2DBC entity mapped to the `event_promoter` join table.
 *
 * Uses a surrogate auto-generated `id` column so that Spring Data R2DBC
 * correctly detects new entities (id = null → INSERT). A unique constraint
 * on (event_id, promoter_id) preserves the logical composite key.
 */
@Table("event_promoter")
data class EventPromoterEntity(
    @Id val id: Long? = null,
    val eventId: Long,
    val promoterId: Long
)
