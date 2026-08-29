package de.norm.events.artist

import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Compact artist representation embedded in event lineups and returned by the artist list.
 */
@Schema(description = "Compact artist summary")
data class ArtistSummaryResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "the-adicts")
    val slug: String,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "URL of the artist's photo or band logo")
    val imageUrl: String?,
    @Schema(
        description =
            "Alternative formats of the same image, best first, for a <picture> element. Empty " +
                "when the image is not cached, in which case `imageUrl` is all there is."
    )
    val imageSources: List<ImageSourceResponse>
) {
    companion object {
        fun fromEntity(
            entity: ArtistEntity,
            image: ServedImage
        ): ArtistSummaryResponse =
            ArtistSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted artist must have an ID" },
                slug = entity.slug,
                name = entity.name,
                imageUrl = image.url,
                imageSources = image.sources
            )
    }
}

/**
 * Full artist representation for the artist detail page.
 *
 * Events featuring this artist are not embedded — the frontend fetches them via
 * `GET /events?artist=<slug>`.
 */
@Schema(description = "Full artist detail")
data class ArtistDetailResponse(
    @Schema(description = "Database primary key", example = "7")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "the-adicts")
    val slug: String,
    @Schema(description = "Stage name or band name", example = "The Adicts")
    val name: String,
    @Schema(description = "Biography or description text")
    val description: String?,
    @Schema(description = "URL of the artist's photo or band logo")
    val imageUrl: String?,
    @Schema(
        description =
            "Alternative formats of the same image, best first, for a <picture> element. Empty " +
                "when the image is not cached, in which case `imageUrl` is all there is."
    )
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = "URL of the artist's official homepage")
    val websiteUrl: String?,
    @Schema(description = "URL of the artist's Facebook page")
    val facebookUrl: String?,
    @Schema(description = "URL of the artist's Instagram profile")
    val instagramUrl: String?,
    @Schema(description = "URL of the artist's YouTube channel")
    val youtubeUrl: String?
) {
    companion object {
        fun fromEntity(
            entity: ArtistEntity,
            image: ServedImage
        ): ArtistDetailResponse =
            ArtistDetailResponse(
                id = requireNotNull(entity.id) { "Persisted artist must have an ID" },
                slug = entity.slug,
                name = entity.name,
                description = entity.description,
                imageUrl = image.url,
                imageSources = image.sources,
                websiteUrl = entity.websiteUrl,
                facebookUrl = entity.facebookUrl,
                instagramUrl = entity.instagramUrl,
                youtubeUrl = entity.youtubeUrl
            )
    }
}
