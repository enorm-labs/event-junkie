package de.norm.events.festival

import io.swagger.v3.oas.annotations.media.Schema

/** The festival an event belongs to, as an event response embeds it (#3273). */
@Schema(description = "The festival an event belongs to")
data class FestivalSummaryResponse(
    @Schema(description = "URL-friendly identifier, the value of the events list's `festival` filter", example = "fete-de-la-musique-2027")
    val slug: String,
    @Schema(description = "Display name of the festival", example = "Fête de la Musique 2027")
    val name: String
) {
    companion object {
        fun fromEntity(entity: FestivalEntity): FestivalSummaryResponse = FestivalSummaryResponse(slug = entity.slug, name = entity.name)
    }
}
