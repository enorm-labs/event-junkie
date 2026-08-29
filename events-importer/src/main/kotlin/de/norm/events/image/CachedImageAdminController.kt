package de.norm.events.image

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The deletion route the venue opt-out needs.
 *
 * Under `/api/admin` like every other admin endpoint, so no Ingress path reaches it and it is
 * answered through a port-forward. That is what keeps a route that deletes images off the internet.
 */
@RestController
@RequestMapping("/api/admin/images")
@Tag(name = "Admin: Cached Images", description = "Venue takedown and the orphan sweep (ADR-019)")
class CachedImageAdminController(
    private val removalService: ImageRemovalService,
    private val sweepProperties: ImageSweepProperties
) {
    /**
     * Removes every cached image the events of one venue point at.
     *
     * **Run this before disabling the source, not after.** The images are found through the venue's
     * events, so clearing `image_url` or deleting the events first leaves nothing to join on. The
     * sweep collects them either way, on its own schedule rather than now.
     *
     * A venue with no cached image is a 200 reporting zero, not a 404. The caller asked for that
     * venue to hold no images, and it holds none.
     */
    @DeleteMapping("/venues/{venueSlug}")
    @Operation(summary = "Delete the cached images of one venue (SCRAPING_POSITION.md §5)")
    suspend fun takeDown(
        @PathVariable venueSlug: String
    ): ImageRemovalResponse = ImageRemovalResponse.of(removalService.takeDown(venueSlug), deleted = true)

    /**
     * Runs one orphan sweep now, instead of waiting for the tick.
     *
     * Reports rather than deletes while `app.images.sweep.enabled` is false, which is the default.
     */
    @PostMapping("/sweep")
    @Operation(summary = "Run one orphan sweep over the rows and the bucket")
    suspend fun sweep(): ImageRemovalResponse = ImageRemovalResponse.of(removalService.sweep(), deleted = sweepProperties.enabled)
}
