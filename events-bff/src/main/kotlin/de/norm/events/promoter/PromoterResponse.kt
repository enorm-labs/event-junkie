package de.norm.events.promoter

import de.norm.events.image.IMAGE_ATTRIBUTION_DESCRIPTION
import de.norm.events.image.IMAGE_LICENCE_ID_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCES_DESCRIPTION
import de.norm.events.image.IMAGE_SOURCE_URL_DESCRIPTION
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
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
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
    @Schema(description = IMAGE_ATTRIBUTION_DESCRIPTION, example = "Photographer Name, via Wikimedia Commons")
    val imageAttribution: String?,
    @Schema(description = IMAGE_LICENCE_ID_DESCRIPTION, example = "CC-BY-SA-4.0")
    val imageLicenceId: String?,
    @Schema(description = IMAGE_SOURCE_URL_DESCRIPTION, example = "https://commons.wikimedia.org/wiki/File:Example.jpg")
    val imageSourceUrl: String?,
    @Schema(description = IMAGE_SOURCES_DESCRIPTION)
    val imageSources: List<ImageSourceResponse>,
    @Schema(description = INTRINSIC_WIDTH_DESCRIPTION, example = "1200")
    val intrinsicWidth: Int?,
    @Schema(description = INTRINSIC_HEIGHT_DESCRIPTION, example = "630")
    val intrinsicHeight: Int?,
    @Schema(description = "Short prose description of the promoter")
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`. Null when the language is unknown.", example = "en")
    val descriptionLanguage: String?,
    @Schema(
        description =
            "The same description in the other language. Written by hand, not machine-translated, " +
                "so it carries no origin and needs no disclosure (#328).",
        example = "Die Hausagentur von Lido, Astra und Bi Nuu."
    )
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "de")
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
    @Schema(description = "Database primary key", example = "3")
    val id: Long,
    @Schema(description = "URL-friendly identifier", example = "36-concerts")
    val slug: String,
    @Schema(description = "Display name of the promoter", example = "36 Concerts")
    val name: String,
    @Schema(description = "URL of the promoter's website or social page")
    val websiteUrl: String?,
    @Schema(description = "Short prose description of the promoter")
    val description: String?,
    @Schema(description = "Language of `description`: `de` or `en`. Null when the language is unknown.", example = "en")
    val descriptionLanguage: String?,
    @Schema(description = "The same description in the other language, written by hand (#328).")
    val descriptionAlt: String?,
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`. Null exactly when `descriptionAlt` is.", example = "de")
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
