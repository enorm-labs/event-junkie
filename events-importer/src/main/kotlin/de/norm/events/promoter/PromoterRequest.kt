package de.norm.events.promoter

import de.norm.events.common.AttributableImage
import de.norm.events.common.AttributedImage
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.Instant

/**
 * Request body for creating or updating a promoter.
 *
 * Only includes user-provided fields — `id`, `createdAt`, and `updatedAt`
 * are managed by the database. For updates, the `id` is taken from the path parameter.
 * Slugs are a server-side concern computed by the service layer, so they are not
 * part of the request DTO.
 */
@Schema(description = "Request body for creating or updating a promoter")
@AttributedImage
data class PromoterRequest(
    @field:NotBlank(message = "Promoter name must not be blank")
    @field:Size(max = 255, message = "Promoter name must not exceed 255 characters")
    @Schema(description = "Display name of the promoter", example = "36 Concerts", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:Size(max = 2048, message = "Website URL must not exceed 2048 characters")
    @Schema(description = "URL of the promoter's website or social page", example = "https://www.facebook.com/36Concerts/")
    val websiteUrl: String? = null,
    @field:Size(max = 2048, message = "Image URL must not exceed 2048 characters")
    @Schema(description = "URL of the promoter's logo image", example = "https://example.com/36-concerts-logo.jpg")
    override val imageUrl: String? = null,
    @field:Size(max = 500, message = "Image attribution must not exceed 500 characters")
    @Schema(description = "Who to credit for `imageUrl`, worded as the archive publishes it", example = "Photographer Name, via Wikimedia Commons")
    override val imageAttribution: String? = null,
    @field:Size(max = 40, message = "Image licence identifier must not exceed 40 characters")
    @Schema(description = "SPDX identifier of the licence `imageUrl` is published under", example = "CC-BY-SA-4.0")
    override val imageLicenceId: String? = null,
    @field:Size(max = 2048, message = "Image source URL must not exceed 2048 characters")
    @Schema(
        description = "The image's description page, which the rendered credit links to",
        example = "https://commons.wikimedia.org/wiki/File:Example.jpg"
    )
    override val imageSourceUrl: String? = null,
    @field:Size(max = 4000, message = "Description must not exceed 4000 characters")
    @Schema(
        description = "Short prose description of the promoter, shown on the detail page",
        example = "The in-house agency of Lido, Astra and Bi Nuu, booking indie and rock in Kreuzberg since 2007."
    )
    val description: String? = null,
    @field:Pattern(regexp = "de|en", message = "Description language must be 'de' or 'en'")
    @Schema(description = "Language of `description`: `de` or `en`", example = "en")
    val descriptionLanguage: String? = null,
    @field:Size(max = 4000, message = "Alternate description must not exceed 4000 characters")
    @Schema(
        description = "The same description in the other language. Hand-written, not machine-translated (ADR-027 covers event text, not this).",
        example = "Die Hausagentur von Lido, Astra und Bi Nuu, bucht seit 2007 Indie und Rock in Kreuzberg."
    )
    val descriptionAlt: String? = null,
    @field:Pattern(regexp = "de|en", message = "Alternate description language must be 'de' or 'en'")
    @Schema(description = "Language of `descriptionAlt`: `de` or `en`", example = "de")
    val descriptionAltLanguage: String? = null,
    @Schema(
        description =
            "When a person last reviewed this row: name as the promoter spells it, kind decided, website recorded or absent " +
                "on purpose. Null for a row nobody looked at. Set by the review, never by an import (#1336).",
        example = "2026-09-11T18:00:00Z"
    )
    val reviewedAt: Instant? = null
) : AttributableImage {
    /** Copies every field of this request onto [base] under [slug]: the one mapping `create` and `update` share (#3409). */
    fun applyTo(
        base: Promoter,
        slug: String
    ): Promoter =
        base.copy(
            name = name,
            slug = slug,
            websiteUrl = websiteUrl,
            imageUrl = imageUrl,
            imageAttribution = imageAttribution,
            imageLicenceId = imageLicenceId,
            imageSourceUrl = imageSourceUrl,
            description = description,
            descriptionLanguage = descriptionLanguage,
            descriptionAlt = descriptionAlt,
            descriptionAltLanguage = descriptionAltLanguage,
            reviewedAt = reviewedAt
        )
}
