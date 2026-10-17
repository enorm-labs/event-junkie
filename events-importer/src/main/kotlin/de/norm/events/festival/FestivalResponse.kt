package de.norm.events.festival

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant
import java.time.LocalDate

@Schema(description = "A festival: one date range across many venues")
data class FestivalResponse(
    @Schema(description = "Database primary key", example = "1")
    val id: Long,
    @Schema(description = "URL-friendly identifier, derived from the name", example = "fete-de-la-musique-2027")
    val slug: String,
    @Schema(description = "Display name of the festival", example = "Fête de la Musique 2027")
    val name: String,
    @Schema(description = "The festival's own programme page", example = "https://www.fetedelamusique.de/")
    val url: String,
    @Schema(description = "First day, Berlin date", example = "2027-06-21")
    val firstDay: LocalDate,
    @Schema(description = "Last day, Berlin date", example = "2027-06-21")
    val lastDay: LocalDate,
    @Schema(description = "The home-page notice in German")
    val noticeDe: String,
    @Schema(description = "The home-page notice in English")
    val noticeEn: String,
    @Schema(description = "Timestamp when this record was first created")
    val createdAt: Instant?,
    @Schema(description = "Timestamp when this record was last modified")
    val updatedAt: Instant?,
    @Schema(description = "How many events an operator linked to the festival, past ones included", example = "42")
    val eventCount: Long = 0
) {
    companion object {
        fun fromEntity(
            entity: FestivalEntity,
            eventCount: Long = 0
        ): FestivalResponse =
            FestivalResponse(
                id = requireNotNull(entity.id) { "Persisted festival must have an ID" },
                slug = entity.slug,
                name = entity.name,
                url = entity.url,
                firstDay = entity.firstDay,
                lastDay = entity.lastDay,
                noticeDe = entity.noticeDe,
                noticeEn = entity.noticeEn,
                createdAt = entity.createdAt,
                updatedAt = entity.updatedAt,
                eventCount = eventCount
            )
    }
}
