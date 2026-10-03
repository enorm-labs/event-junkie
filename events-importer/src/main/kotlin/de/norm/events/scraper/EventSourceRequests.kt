package de.norm.events.scraper

import com.fasterxml.jackson.annotation.JsonIgnore
import de.norm.events.licence.SourceLicence
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.hibernate.validator.constraints.URL
import java.time.LocalTime

/**
 * Request body for creating a new event source.
 *
 * Only includes user-provided fields — `id`, `slug`, `status`, `retryCount`,
 * ETag/Last-Modified caching headers, and timestamps are managed by the server.
 * Slugs are auto-generated from the source name by the service layer.
 */
@Schema(description = "Request body for creating a new event source")
data class EventSourceCreateRequest(
    @field:NotNull(message = "Venue ID must not be null")
    @Schema(description = "Database ID of the associated venue", example = "42", requiredMode = Schema.RequiredMode.REQUIRED)
    val venueId: Long,
    @field:NotBlank(message = "Name must not be blank")
    @field:Size(max = 255, message = "Name must not exceed 255 characters")
    @Schema(description = "Human-readable name of the event source", example = "Privatclub Website", requiredMode = Schema.RequiredMode.REQUIRED)
    val name: String,
    @field:NotBlank(message = "URL must not be blank")
    @field:Size(max = 2048, message = "URL must not exceed 2048 characters")
    @field:URL(message = "URL must be a valid URL")
    @field:Pattern(regexp = "https?://.*", message = "URL must use HTTP or HTTPS")
    @Schema(description = "The event listing page URL to scrape", example = "https://privatclub-berlin.de/", requiredMode = Schema.RequiredMode.REQUIRED)
    val url: String,
    @field:NotBlank(message = "Source type must not be blank")
    @field:Size(max = 255, message = "Source type must not exceed 255 characters")
    @Schema(
        description = "EventSource enum value identifying the importer to use (e.g. CASSIOPEIA)",
        example = "CASSIOPEIA",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val sourceType: String,
    @Schema(description = "Whether this source should be actively scraped (defaults to true)", example = "true")
    val enabled: Boolean = true,
    @Schema(description = "Import interval in minutes (defaults to 1440 = daily)", example = "1440")
    @field:Min(value = 1, message = "Import interval must be at least 1 minute")
    val importIntervalMinutes: Int = EventSourceEntity.DEFAULT_IMPORT_INTERVAL_MINUTES,
    @Schema(description = "Maximum retry attempts before giving up (defaults to 3)", example = "3")
    @field:Min(value = 0, message = "Max retries must not be negative")
    val maxRetries: Int = EventSourceEntity.DEFAULT_MAX_RETRIES
)

/**
 * Request body for updating an event source's configuration.
 */
@Schema(description = "Partial update for an event source's configuration")
data class EventSourceUpdateRequest(
    @Schema(description = "Whether this source should be actively scraped", example = "true")
    val enabled: Boolean? = null,
    @Schema(description = "Import interval in minutes (1440 = daily)", example = "720")
    @field:Min(value = 1, message = "Import interval must be at least 1 minute")
    val importIntervalMinutes: Int? = null,
    @Schema(description = "Maximum retry attempts before giving up", example = "5")
    @field:Min(value = 0, message = "Max retries must not be negative")
    val maxRetries: Int? = null,
    @Schema(
        description =
            "Local time, in the global zone, from which a scheduled import of this source may start. " +
                "Sent with importWindowEnd. Equal values mean the whole day (#791)",
        example = "03:00"
    )
    val importWindowStart: LocalTime? = null,
    @Schema(description = "Local time, exclusive, at which this source's import window closes", example = "05:00")
    val importWindowEnd: LocalTime? = null,
    @Schema(description = "True drops this source's own window, so the global one applies again", example = "true")
    val useDefaultImportWindow: Boolean? = null,
    @Schema(
        description =
            "Whether this source's event descriptions may be republished. PROHIBITED withholds them " +
                "from every public response. Null leaves the current value unchanged (#283)",
        example = "UNCLEAR"
    )
    val descriptionLicence: SourceLicence? = null,
    @Schema(
        description = "The same question for this source's images, answered separately",
        example = "PROHIBITED"
    )
    val imageLicence: SourceLicence? = null,
    @Schema(
        description =
            "Whether this source grants translation of its descriptions, which is a separate act " +
                "from showing them. Only PERMITTED allows it, so silence and UNCLEAR both decline",
        example = "PERMITTED"
    )
    val translationLicence: SourceLicence? = null,
    @Schema(
        description = "The page the reviewer read. Worth recording even when the answer is UNCLEAR",
        example = "https://example.com/presse"
    )
    val licenceSourceUrl: String? = null,
    @Schema(description = "The sentence that decided it", example = "Pressefotos zur honorarfreien Verwendung")
    val licenceNote: String? = null
) {
    /** A window is set whole or reset, never half set: one half has no partner to pair with. */
    @get:JsonIgnore
    @get:Schema(hidden = true)
    @get:AssertTrue(message = "Send importWindowStart and importWindowEnd together, or useDefaultImportWindow alone")
    val importWindowComplete: Boolean
        get() {
            val halves = listOfNotNull(importWindowStart, importWindowEnd).size
            return if (useDefaultImportWindow == true) halves == 0 else halves != 1
        }
}
