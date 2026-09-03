package de.norm.events.promoter

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Response DTO for a promoter.
 *
 * Decouples the API contract from the domain model so that internal
 * domain changes do not automatically become breaking API changes.
 */
@Schema(description = "Response DTO for a promoter")
data class PromoterResponse(
    @Schema(description = "Database primary key", example = "3")
    val id: Long,
    @Schema(description = "Display name of the promoter", example = "36 Concerts")
    val name: String,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "36-concerts")
    val slug: String,
    @Schema(description = "URL of the promoter's website or social page", example = "https://www.facebook.com/36Concerts/")
    val websiteUrl: String?,
    @Schema(description = "URL of the promoter's logo image", example = "https://example.com/36-concerts-logo.jpg")
    val imageUrl: String?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        /** Converts a domain [Promoter] to its API response representation. */
        fun fromDomain(promoter: Promoter): PromoterResponse =
            PromoterResponse(
                id = requireNotNull(promoter.id) { "Persisted promoter must have an ID" },
                name = promoter.name,
                slug = promoter.slug,
                websiteUrl = promoter.websiteUrl,
                imageUrl = promoter.imageUrl,
                createdAt = promoter.createdAt,
                updatedAt = promoter.updatedAt
            )
    }
}
