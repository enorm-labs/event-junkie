package de.norm.events.importing

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The party features of an event, read and set by hand (#3066). Here rather than in `EventController`,
 * because the `event_feature` and flag repositories belong to this module, and `event` may not depend on it.
 */
@RestController
@RequestMapping("/api/admin/events/{id}/features")
@Tag(name = "Admin: Events", description = "Admin CRUD endpoints for managing events")
class EventFeatureController(
    private val eventFeatureService: EventFeatureService
) {
    @GetMapping
    @Operation(summary = "The party features of an event, the uncertain cues beside them, and whether they are pinned")
    suspend fun find(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long
    ): EventFeaturesResponse = eventFeatureService.find(id)

    @PutMapping
    @Operation(
        summary = "Replace the party features of an event, and pin them",
        description =
            "Takes the full list: a feature left out is removed, and an empty list clears them all. The importer keeps " +
                "pinned features; `DELETE /api/admin/events/{id}/pins/features` hands them back to the keyword rules at the " +
                "next import. Deletes the event's UNCERTAIN_PARTY_FEATURE flags. An unknown slug is a 400 naming the valid ones."
    )
    suspend fun set(
        @Parameter(description = "Database ID of the event.", example = "1")
        @PathVariable id: Long,
        @RequestBody request: EventFeaturesRequest
    ): EventFeaturesResponse = eventFeatureService.set(id, request.features)
}
