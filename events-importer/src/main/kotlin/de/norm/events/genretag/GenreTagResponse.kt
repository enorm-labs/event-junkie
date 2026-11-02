package de.norm.events.genretag

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Response DTO for a genre tag.
 */
@Schema(description = "Response DTO for a normalized genre tag")
data class GenreTagResponse(
    @Schema(description = "Database primary key", example = "1")
    val id: Long,
    @Schema(description = "Canonical display name", example = "Hip Hop")
    val name: String,
    @Schema(description = "URL-friendly identifier", example = "hip-hop")
    val slug: String,
    @Schema(description = "Slug of the filter family the tag belongs to, or null when it belongs to none", example = "hip-hop")
    val family: String?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        fun fromDomain(genreTag: GenreTag): GenreTagResponse =
            GenreTagResponse(
                id = genreTag.requiredId(),
                name = genreTag.name,
                slug = genreTag.slug,
                family = genreTag.family?.slug,
                createdAt = genreTag.createdAt,
                updatedAt = genreTag.updatedAt
            )
    }
}
