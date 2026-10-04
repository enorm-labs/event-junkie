package de.norm.events.venue

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Admin endpoints for a venue's character tags (#2379), a sub-resource rather than a field of
 * `VenueRequest`: a venue PUT replaces every field, and a script that predates the tags would
 * otherwise clear them.
 */
@RestController
@RequestMapping("/api/admin/venues/{id}/character-tags")
@Tag(name = "Admin: Venues", description = "Admin CRUD endpoints for managing venues")
class VenueCharacterTagController(
    private val service: VenueCharacterTagService
) {
    @GetMapping
    @Operation(summary = "List a venue's character tags with the source URL of each")
    suspend fun list(
        @Parameter(description = "Database ID of the venue.", example = "1")
        @PathVariable id: Long
    ): List<VenueCharacterTagResponse> = service.list(id)

    @PutMapping("/{tag}")
    @Operation(summary = "Set a character tag on a venue, or replace its source URL")
    suspend fun set(
        @Parameter(description = "Database ID of the venue.", example = "1")
        @PathVariable id: Long,
        @Parameter(description = "Character tag slug, one of VenueCharacterTag, such as queer or awareness-team.", example = "queer")
        @PathVariable tag: String,
        @Valid @RequestBody request: VenueCharacterTagRequest
    ): VenueCharacterTagResponse = service.set(id, tag, request.sourceUrl)

    @DeleteMapping("/{tag}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a character tag from a venue")
    suspend fun remove(
        @Parameter(description = "Database ID of the venue.", example = "1")
        @PathVariable id: Long,
        @Parameter(description = "Character tag slug.", example = "queer")
        @PathVariable tag: String
    ) {
        service.remove(id, tag)
    }
}

@Schema(description = "The venue's own page that states the tag")
data class VenueCharacterTagRequest(
    @field:NotBlank(message = "Source URL must not be blank")
    @field:Size(max = 2048, message = "Source URL must not exceed 2048 characters")
    @field:Pattern(regexp = "^https?://\\S+$", message = "Source URL must be an http or https URL")
    @Schema(
        description = "URL of the venue's own page that states the tag",
        example = "https://www.so36.com/about",
        requiredMode = Schema.RequiredMode.REQUIRED
    )
    val sourceUrl: String
)

@Schema(description = "A character tag on a venue and the page it rests on")
data class VenueCharacterTagResponse(
    @Schema(description = "Character tag slug", example = "queer")
    val tag: String,
    @Schema(description = "URL of the venue's own page that states the tag", example = "https://www.so36.com/about")
    val sourceUrl: String
)
