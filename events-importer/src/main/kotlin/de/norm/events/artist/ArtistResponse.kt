package de.norm.events.artist

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Response DTO for an artist.
 *
 * Decouples the API contract from the domain model so that internal
 * domain changes do not automatically become breaking API changes.
 */
@Schema(description = "Response DTO for an artist")
data class ArtistResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "the-adicts")
    val slug: String,
    @Schema(description = "Biography or description text", example = "Formed in Ipswich in the late 1970s…")
    val description: String?,
    @Schema(description = "URL of the artist's photo or band logo", example = "https://example.com/adicts.jpg")
    val imageUrl: String?,
    @Schema(description = "URL of the artist's official homepage", example = "https://theadicts.net/")
    val websiteUrl: String?,
    @Schema(description = "URL of the artist's Facebook page", example = "https://www.facebook.com/theadicts")
    val facebookUrl: String?,
    @Schema(description = "URL of the artist's Instagram profile", example = "https://www.instagram.com/theadictsofficial/")
    val instagramUrl: String?,
    @Schema(description = "URL of the artist's YouTube channel", example = "https://www.youtube.com/@theadictsofficial")
    val youtubeUrl: String?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        /** Converts a domain [Artist] to its API response representation. */
        fun fromDomain(artist: Artist): ArtistResponse =
            ArtistResponse(
                id = requireNotNull(artist.id) { "Persisted artist must have an ID" },
                name = artist.name,
                slug = artist.slug,
                description = artist.description,
                imageUrl = artist.imageUrl,
                websiteUrl = artist.websiteUrl,
                facebookUrl = artist.facebookUrl,
                instagramUrl = artist.instagramUrl,
                youtubeUrl = artist.youtubeUrl,
                createdAt = artist.createdAt,
                updatedAt = artist.updatedAt
            )
    }
}
