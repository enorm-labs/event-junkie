package de.norm.events.promoter

import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Compact promoter representation embedded in event detail responses and returned by the promoter list.
 */
@Schema(description = "Compact promoter summary")
data class PromoterSummaryResponse(
    @Schema(description = "Database primary key", example = "3")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "36-concerts")
    val slug: String,
    @Schema(description = "Display name of the promoter", example = "36 Concerts")
    val name: String,
    @Schema(description = "URL of the promoter's website or social page")
    val websiteUrl: String?,
    @Schema(description = "URL of the promoter's logo image")
    val imageUrl: String?,
    @Schema(
        description =
            "Alternative formats of the same image, best first, for a <picture> element. Empty " +
                "when the image is not cached, in which case `imageUrl` is all there is."
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
    val intrinsicHeight: Int?
) {
    companion object {
        fun fromEntity(
            entity: PromoterEntity,
            image: ServedImage
        ): PromoterSummaryResponse =
            PromoterSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted promoter must have an ID" },
                slug = entity.slug,
                name = entity.name,
                websiteUrl = entity.websiteUrl,
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight
            )
    }
}

/**
 * Full promoter representation for the promoter detail page.
 *
 * Events from this promoter are intentionally not embedded — the frontend fetches them via
 * `GET /events?promoter=<slug>`, which keeps modules decoupled and reuses the event filter.
 */
@Schema(description = "Full promoter detail")
data class PromoterDetailResponse(
    @Schema(description = "Database primary key", example = "3")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "36-concerts")
    val slug: String,
    @Schema(description = "Display name of the promoter", example = "36 Concerts")
    val name: String,
    @Schema(description = "URL of the promoter's website or social page")
    val websiteUrl: String?,
    @Schema(description = "URL of the promoter's logo image")
    val imageUrl: String?,
    @Schema(
        description =
            "Alternative formats of the same image, best first, for a <picture> element. Empty " +
                "when the image is not cached, in which case `imageUrl` is all there is."
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
    val intrinsicHeight: Int?
) {
    companion object {
        fun fromEntity(
            entity: PromoterEntity,
            image: ServedImage
        ): PromoterDetailResponse =
            PromoterDetailResponse(
                id = requireNotNull(entity.id) { "Persisted promoter must have an ID" },
                slug = entity.slug,
                name = entity.name,
                websiteUrl = entity.websiteUrl,
                imageUrl = image.url,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight
            )
    }
}
