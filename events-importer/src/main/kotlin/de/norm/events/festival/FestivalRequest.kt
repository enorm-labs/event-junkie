package de.norm.events.festival

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * Request body for creating or replacing a festival. The slug is derived from [name] on the
 * server, so it is not part of the request.
 */
@Schema(description = "Request body for creating or replacing a festival")
data class FestivalRequest(
    @field:NotBlank(message = "Festival name must not be blank")
    @field:Size(max = 255, message = "Festival name must not exceed 255 characters")
    @Schema(description = "Display name of the festival", example = "Fête de la Musique", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:NotBlank(message = "URL must not be blank")
    @field:Size(max = 2048, message = "URL must not exceed 2048 characters")
    @field:Pattern(regexp = "https?://\\S+", message = "URL must be an http:// or https:// address")
    @Schema(
        description = "The festival's own programme page",
        example = "https://www.fetedelamusique.de/",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val url: String,
    @field:NotNull(message = "First day must be set")
    @Schema(description = "First day of the festival, Berlin date", example = "2027-06-21", requiredMode = Schema.RequiredMode.REQUIRED)
    val firstDay: LocalDate?,
    @field:NotNull(message = "Last day must be set")
    @Schema(
        description = "Last day of the festival, Berlin date. Equal to `firstDay` for a one-day festival.",
        example = "2027-06-21",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val lastDay: LocalDate?,
    @field:NotBlank(message = "German notice must not be blank")
    @field:Size(max = 500, message = "German notice must not exceed 500 characters")
    @Schema(
        description = "One or two sentences for the home page, in German",
        example = "Am längsten Tag des Jahres spielt Musik an hunderten Orten der Stadt, draußen und kostenlos.",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val noticeDe: String,
    @field:NotBlank(message = "English notice must not be blank")
    @field:Size(max = 500, message = "English notice must not exceed 500 characters")
    @Schema(
        description = "One or two sentences for the home page, in English",
        example = "On the longest day of the year, music plays at hundreds of places across the city, outdoors and free.",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val noticeEn: String
) {
    /** Copies every field onto [base] under [slug]: the one mapping `create` and `update` share. */
    fun applyTo(
        base: FestivalEntity?,
        slug: String
    ): FestivalEntity {
        val first = requireNotNull(firstDay) { "firstDay is validated as set" }
        val last = requireNotNull(lastDay) { "lastDay is validated as set" }
        if (last < first) throw InvalidFestivalDaysException(first, last)
        return FestivalEntity(
            id = base?.id,
            slug = slug,
            name = name.trim(),
            url = url.trim(),
            firstDay = first,
            lastDay = last,
            noticeDe = noticeDe.trim(),
            noticeEn = noticeEn.trim(),
            createdAt = base?.createdAt,
            updatedAt = base?.updatedAt
        )
    }
}
