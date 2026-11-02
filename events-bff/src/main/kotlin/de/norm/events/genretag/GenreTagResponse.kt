package de.norm.events.genretag

import io.swagger.v3.oas.annotations.media.Schema

/**
 * Genre tag representation for the frontend filter dropdown.
 */
@Schema(description = "A normalized genre tag")
data class GenreTagResponse(
    @Schema(description = "Database primary key", example = "1")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "hip-hop")
    val slug: String,
    @Schema(description = "Canonical display name", example = "Hip Hop")
    val name: String,
    @Schema(description = "Slug of the filter family the tag belongs to; null means the tag is offered in neither select", example = "hip-hop")
    val family: String?
) {
    companion object {
        fun fromEntity(entity: GenreTagEntity): GenreTagResponse =
            GenreTagResponse(
                id = entity.requiredId(),
                slug = entity.slug,
                name = entity.name,
                family = entity.family
            )
    }
}
