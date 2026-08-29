package de.norm.events.venue

import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * Compact venue representation embedded in event responses and returned by the venue list.
 */
@Schema(description = "Compact venue summary")
data class VenueSummaryResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
    @Schema(description = "City where the venue is located", example = "Berlin")
    val city: String,
    @Schema(description = "Street address of the venue", example = "Revaler Str. 99")
    val address: String?,
    @Schema(description = "Berlin borough (Bezirk) as a canonical slug", example = "friedrichshain-kreuzberg")
    val district: String?,
    @Schema(description = "URL of the venue's logo or photo")
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
            entity: VenueEntity,
            image: ServedImage
        ): VenueSummaryResponse =
            VenueSummaryResponse(
                id = requireNotNull(entity.id) { "Persisted venue must have an ID" },
                slug = entity.slug,
                name = entity.name,
                city = entity.city,
                address = entity.address,
                district = entity.district,
                imageUrl = image.url,
                imageSources = image.sources
            )
    }
}

/**
 * Full venue representation for the venue detail page.
 *
 * Events at this venue are intentionally not embedded — the frontend fetches them via
 * `GET /events?venue=<slug>`, which keeps modules decoupled and reuses the event filter.
 */
@Schema(description = "Full venue detail")
data class VenueDetailResponse(
    @Schema(description = "Database primary key", example = "42")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "astra-kulturhaus")
    val slug: String,
    @Schema(description = "Display name of the venue", example = "Astra Kulturhaus")
    val name: String,
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
    @Schema(description = "URL of the venue's official website")
    val websiteUrl: String?,
    @Schema(description = "URL of the venue's logo or photo")
    val imageUrl: String?,
    @Schema(
        description =
            "Alternative formats of the same image, best first, for a <picture> element. Empty " +
                "when the image is not cached, in which case `imageUrl` is all there is."
    )
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = "Short prose description of the venue")
    val description: String?
) {
    companion object {
        fun fromEntity(
            entity: VenueEntity,
            image: ServedImage
        ): VenueDetailResponse =
            VenueDetailResponse(
                id = requireNotNull(entity.id) { "Persisted venue must have an ID" },
                slug = entity.slug,
                name = entity.name,
                address = entity.address,
                city = entity.city,
                postalCode = entity.postalCode,
                district = entity.district,
                latitude = entity.latitude,
                longitude = entity.longitude,
                websiteUrl = entity.websiteUrl,
                imageUrl = image.url,
                imageSources = image.sources,
                description = entity.description
            )
    }
}
