package de.norm.events.scraper

import io.swagger.v3.oas.annotations.media.Schema
import java.time.Instant

/**
 * Response DTO for event source status information.
 */
@Schema(description = "Event source configuration and status")
data class EventSourceResponse(
    @Schema(description = "Database ID of the event source", example = "1")
    val id: Long,
    @Schema(description = "Database ID of the associated venue", example = "42")
    val venueId: Long,
    @Schema(description = "Human-readable name of the event source", example = "Privatclub Website")
    val name: String,
    @Schema(description = "URL-friendly unique key", example = "privatclub")
    val slug: String,
    @Schema(description = "The event listing page URL being scraped", example = "https://privatclub-berlin.de/")
    val url: String,
    @Schema(description = "EventSource enum value identifying the importer", example = "CASSIOPEIA")
    val sourceType: String,
    @Schema(description = "Whether this source is actively scraped", example = "true")
    val enabled: Boolean,
    @Schema(description = "Import interval in minutes (1440 = daily)", example = "1440")
    val importIntervalMinutes: Int,
    @Schema(description = "Current import status", example = "SUCCESS")
    val status: String,
    @Schema(description = "Timestamp of the last completed import, successful or not")
    val lastImportAt: Instant?,
    @Schema(
        description =
            "Timestamp of the last import that succeeded. Differs from lastImportAt whenever the " +
                "most recent run failed, which is when a steward needs it: how long this source has " +
                "actually been broken."
    )
    val lastSuccessAt: Instant?,
    @Schema(description = "Number of events imported in the last successful run", example = "12")
    val lastEventCount: Int?,
    @Schema(description = "Error message from the last failed import")
    val lastError: String?,
    @Schema(
        description =
            "When a run last found materially less of some field than this source normally " +
                "publishes (#472), or null. Independent of status: a flagged source is one whose " +
                "runs all SUCCEED — that is the failure being caught"
    )
    val flaggedAt: Instant?,
    @Schema(
        description = "Which fields dropped and by how much",
        example = "genre 5% of 40 events, baseline 98%"
    )
    val flagReason: String?,
    @Schema(description = "Number of consecutive failures", example = "0")
    val retryCount: Int,
    @Schema(description = "Maximum retry attempts before giving up", example = "3")
    val maxRetries: Int
) {
    companion object {
        fun fromEntity(entity: EventSourceEntity): EventSourceResponse =
            EventSourceResponse(
                id = requireNotNull(entity.id) { "Persisted entity must have an ID" },
                venueId = entity.venueId,
                name = entity.name,
                slug = entity.slug,
                url = entity.url,
                sourceType = entity.sourceType,
                enabled = entity.enabled,
                importIntervalMinutes = entity.importIntervalMinutes,
                status = entity.status,
                lastImportAt = entity.lastImportAt,
                lastSuccessAt = entity.lastSuccessAt,
                lastEventCount = entity.lastEventCount,
                lastError = entity.lastError,
                flaggedAt = entity.flaggedAt,
                flagReason = entity.flagReason,
                retryCount = entity.retryCount,
                maxRetries = entity.maxRetries
            )
    }
}

/**
 * Response DTO for an import run result.
 */
@Schema(description = "Result of an import run for a single source")
data class ImportResultResponse(
    @Schema(description = "Slug of the event source", example = "privatclub")
    val sourceSlug: String,
    @Schema(description = "Whether the import was executed (false if page was not modified)", example = "true")
    val imported: Boolean,
    @Schema(description = "Number of events imported or updated", example = "12")
    val eventCount: Int,
    @Schema(description = "Error message if the import failed")
    val error: String? = null
)

/**
 * Response DTO acknowledging that an import was accepted and started in the background.
 *
 * Manual import triggers are fire-and-forget (HTTP `202 Accepted`): the import runs
 * asynchronously so its duration is decoupled from the request. Poll the event source's
 * status via `GET /api/admin/event-sources/{slug}` to observe progress and the outcome.
 */
@Schema(description = "Acknowledgement that a background import was accepted and started")
data class ImportTriggeredResponse(
    @Schema(description = "Human-readable acknowledgement message", example = "Import started for source 'privatclub'")
    val message: String,
    @Schema(description = "Slug of the triggered source, or null when all enabled sources were triggered", example = "privatclub")
    val sourceSlug: String? = null
)
