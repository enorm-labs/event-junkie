package de.norm.events.promoter

import de.norm.events.common.IMAGE_ATTRIBUTION_DESCRIPTION
import de.norm.events.common.IMAGE_LICENCE_ID_DESCRIPTION
import de.norm.events.common.IMAGE_SOURCE_URL_DESCRIPTION
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
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(description = "Short prose description of the promoter")
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`", example = "en")
    val descriptionLanguage: String?,
    @Schema(description = "The same description in the other language, written by hand")
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "de")
    val descriptionAltLanguage: String?,
    @Schema(description = "When a person last reviewed this row; null for a row nobody looked at (#1336)")
    val reviewedAt: Instant?,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?
) {
    companion object {
        fun fromDomain(promoter: Promoter): PromoterResponse =
            PromoterResponse(
                id = requireNotNull(promoter.id) { "Persisted promoter must have an ID" },
                name = promoter.name,
                slug = promoter.slug,
                websiteUrl = promoter.websiteUrl,
                imageUrl = promoter.imageUrl,
                imageAttribution = promoter.imageAttribution,
                imageLicenceId = promoter.imageLicenceId,
                imageSourceUrl = promoter.imageSourceUrl,
                description = promoter.description,
                descriptionLanguage = promoter.descriptionLanguage,
                descriptionAlt = promoter.descriptionAlt,
                descriptionAltLanguage = promoter.descriptionAltLanguage,
                reviewedAt = promoter.reviewedAt,
                createdAt = promoter.createdAt,
                updatedAt = promoter.updatedAt
            )
    }
}
