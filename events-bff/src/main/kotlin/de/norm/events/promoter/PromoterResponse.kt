package de.norm.events.promoter

import de.norm.events.common.IMAGE_ATTRIBUTION_DESCRIPTION
import de.norm.events.common.IMAGE_ATTRIBUTION_EXAMPLE
import de.norm.events.common.IMAGE_LICENCE_ID_DESCRIPTION
import de.norm.events.common.IMAGE_LICENCE_ID_EXAMPLE
import de.norm.events.common.IMAGE_SOURCE_URL_DESCRIPTION
import de.norm.events.common.IMAGE_SOURCE_URL_EXAMPLE
import de.norm.events.image.IMAGE_SOURCES_DESCRIPTION
import de.norm.events.image.INTRINSIC_HEIGHT_DESCRIPTION
import de.norm.events.image.INTRINSIC_WIDTH_DESCRIPTION
import de.norm.events.image.ImageSourceResponse
import de.norm.events.image.ServedImage
import io.swagger.v3.oas.annotations.media.Schema

/**
 * Compact promoter representation embedded in event detail responses.
 */
@Schema(description = "Compact promoter summary")
data class PromoterSummaryResponse(
    @Schema(description = ID_DESCRIPTION, example = "3")
    val id: Long,
    @Schema(description = SLUG_DESCRIPTION, example = "36-concerts")
    val slug: String,
    @Schema(description = NAME_DESCRIPTION, example = "36 Concerts")
    val name: String,
    @Schema(description = WEBSITE_URL_DESCRIPTION)
    val websiteUrl: String?,
    @Schema(description = IMAGE_URL_DESCRIPTION)
    val imageUrl: String?,
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = IMAGE_ATTRIBUTION_EXAMPLE)
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = IMAGE_LICENCE_ID_EXAMPLE)
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = IMAGE_SOURCE_URL_EXAMPLE)
    val imageSourceUrl: String?,
    @Schema(description = IMAGE_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
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
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
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
    @Schema(description = ID_DESCRIPTION, example = "3")
    val id: Long,
    @Schema(description = SLUG_DESCRIPTION, example = "36-concerts")
    val slug: String,
    @Schema(description = NAME_DESCRIPTION, example = "36 Concerts")
    val name: String,
    @Schema(description = WEBSITE_URL_DESCRIPTION)
    val websiteUrl: String?,
    @Schema(description = IMAGE_URL_DESCRIPTION)
    val imageUrl: String?,
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = IMAGE_ATTRIBUTION_EXAMPLE)
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = IMAGE_LICENCE_ID_EXAMPLE)
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = IMAGE_SOURCE_URL_EXAMPLE)
    val imageSourceUrl: String?,
    @Schema(description = IMAGE_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?,
    @Schema(description = DESCRIPTION_DESCRIPTION)
    val description: String?,
    @Schema(description = DESCRIPTION_LANGUAGE_DESCRIPTION, example = "en")
    val descriptionLanguage: String?,
    @Schema(
        description =
            "The same description in the other language. Written by hand, not machine-translated, " +
                "so it carries no origin and needs no disclosure (#328).",
        example = "Die Hausagentur von Lido, Astra und Bi Nuu."
    )
    val descriptionAlt: String?,
    @Schema(description = DESCRIPTION_ALT_LANGUAGE_DESCRIPTION, example = "de")
    val descriptionAltLanguage: String?
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
                imageAttribution = entity.imageAttribution,
                imageLicenceId = entity.imageLicenceId,
                imageSourceUrl = entity.imageSourceUrl,
                imageSources = image.sources,
                intrinsicWidth = image.intrinsicWidth,
                intrinsicHeight = image.intrinsicHeight,
                description = entity.description,
                descriptionLanguage = entity.descriptionLanguage,
                descriptionAlt = entity.descriptionAlt,
                descriptionAltLanguage = entity.descriptionAltLanguage
            )
    }
}

/**
 * One row of the promoter list page (#1349): what a card shows, how many of the promoter's events
 * are still to come, and how many start in the next 30 days (#2694). No image: the list draws none. The description fields are the detail
 * response's, so the card picks a language the same way the page does.
 */
@Schema(description = "Promoter list row")
data class PromoterListItemResponse(
    @Schema(description = ID_DESCRIPTION, example = "3")
    val id: Long,
    @Schema(description = SLUG_DESCRIPTION, example = "36-concerts")
    val slug: String,
    @Schema(description = NAME_DESCRIPTION, example = "36 Concerts")
    val name: String,
    @Schema(description = WEBSITE_URL_DESCRIPTION)
    val websiteUrl: String?,
    @Schema(description = DESCRIPTION_DESCRIPTION)
    val description: String?,
    @Schema(description = DESCRIPTION_LANGUAGE_DESCRIPTION, example = "en")
    val descriptionLanguage: String?,
    @Schema(description = "The same description in the other language, written by hand (#328).")
    val descriptionAlt: String?,
    @Schema(description = DESCRIPTION_ALT_LANGUAGE_DESCRIPTION, example = "de")
    val descriptionAltLanguage: String?,
    @Schema(description = "Events from today on that credit this promoter", example = "220")
    val upcomingEventCount: Int,
    @Schema(
        description = "Events crediting this promoter that start within the next 30 days, Berlin time. The list's `upcomingEvents` sort orders by this count.",
        example = "12"
    )
    val upcomingNext30DaysCount: Int
) {
    companion object {
        fun fromEntity(
            entity: PromoterEntity,
            row: PromoterListRow
        ): PromoterListItemResponse =
            PromoterListItemResponse(
                id = requireNotNull(entity.id) { "Persisted promoter must have an ID" },
                slug = entity.slug,
                name = entity.name,
                websiteUrl = entity.websiteUrl,
                description = entity.description,
                descriptionLanguage = entity.descriptionLanguage,
                descriptionAlt = entity.descriptionAlt,
                descriptionAltLanguage = entity.descriptionAltLanguage,
                upcomingEventCount = row.upcomingEventCount,
                upcomingNext30DaysCount = row.upcomingNext30DaysCount
            )
    }
}

private const val ID_DESCRIPTION = "Database primary key"
private const val SLUG_DESCRIPTION = "URL-friendly identifier"
private const val NAME_DESCRIPTION = "Display name of the promoter"
private const val WEBSITE_URL_DESCRIPTION = "URL of the promoter's website or social page"
private const val IMAGE_URL_DESCRIPTION = "URL of the promoter's logo image"
private const val DESCRIPTION_DESCRIPTION = "Short prose description of the promoter"
private const val DESCRIPTION_LANGUAGE_DESCRIPTION = "Language of `description`: `de` or `en`. Null when the language is unknown."
private const val DESCRIPTION_ALT_LANGUAGE_DESCRIPTION = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is."
