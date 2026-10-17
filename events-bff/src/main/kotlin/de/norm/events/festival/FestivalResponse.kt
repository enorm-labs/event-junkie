package de.norm.events.festival

import io.swagger.v3.oas.annotations.media.Schema
import java.time.LocalDate

@Schema(description = "A festival across many venues, for the home-page notice")
data class FestivalResponse(
    @Schema(description = "URL-friendly identifier, derived from the name", example = "fete-de-la-musique-2027")
    val slug: String,
    @Schema(description = "Display name of the festival", example = "Fête de la Musique 2027")
    val name: String,
    @Schema(description = "The festival's own programme page", example = "https://www.fetedelamusique.de/")
    val url: String,
    @Schema(description = "First day, Berlin date", example = "2027-06-21")
    val firstDay: LocalDate,
    @Schema(description = "Last day, Berlin date; equal to `firstDay` for a one-day festival", example = "2027-06-21")
    val lastDay: LocalDate,
    @Schema(description = "One or two sentences for the home page, in German")
    val noticeDe: String,
    @Schema(description = "One or two sentences for the home page, in English")
    val noticeEn: String
) {
    companion object {
        fun fromEntity(entity: FestivalEntity): FestivalResponse =
            FestivalResponse(
                slug = entity.slug,
                name = entity.name,
                url = entity.url,
                firstDay = entity.firstDay,
                lastDay = entity.lastDay,
                noticeDe = entity.noticeDe,
                noticeEn = entity.noticeEn
            )
    }
}
