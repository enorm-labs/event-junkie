package de.norm.events.venue

import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * Response DTO for a venue.
 *
 * Decouples the API contract from the domain model so that internal
 * domain changes do not automatically become breaking API changes.
 */
@Schema(description = "Response DTO for a venue")
data class VenueResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String?,
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String,
    @Schema(description = "Postal code of the venue's address", example = "10245")
    val postalCode: String?,
    @Schema(description = "Berlin borough (Bezirk) as a canonical slug", example = "friedrichshain-kreuzberg")
    val district: String?,
    @Schema(description = "Geographic latitude for map display", example = "52.507242")
    val latitude: BigDecimal?,
    @Schema(description = "Geographic longitude for map display", example = "13.451803")
    val longitude: BigDecimal?,
    @Schema(description = "URL of the venue's official website", example = "https://www.astra-berlin.de")
    val websiteUrl: String?,
    @Schema(description = "URL of the venue's logo or photo", example = "https://example.com/astra-logo.jpg")
    val imageUrl: String?,
    @Schema(
        description = "Short prose description of the venue, shown on the detail page",
        example = "A former power plant turned techno institution in Friedrichshain."
    )
    val description: String?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        /** Converts a domain [Venue] to its API response representation. */
        fun fromDomain(venue: Venue): VenueResponse =
            VenueResponse(
                id = requireNotNull(venue.id) { "Persisted venue must have an ID" },
                name = venue.name,
                slug = venue.slug,
                address = venue.address,
                city = venue.city,
                postalCode = venue.postalCode,
                district = venue.district,
                latitude = venue.latitude,
                longitude = venue.longitude,
                websiteUrl = venue.websiteUrl,
                imageUrl = venue.imageUrl,
                description = venue.description,
                createdAt = venue.createdAt,
                updatedAt = venue.updatedAt
            )
    }
}
