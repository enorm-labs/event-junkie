package de.norm.events.image

import io.swagger.v3.oas.annotations.media.Schema

/** What a takedown or a sweep removed, or would have removed while the sweep only reports. */
@Schema(description = "The result of a venue takedown or an orphan sweep")
data class ImageRemovalResponse(
    @field:Schema(description = "Cached images taken down, or rows found unreferenced by any event", example = "12")
    val images: Int,
    @field:Schema(description = "Objects deleted from the bucket for those images", example = "156")
    val objects: Int,
    @field:Schema(description = "Objects deleted because no row claimed them, which only a sweep finds", example = "3")
    val strays: Int,
    @field:Schema(
        description = "False while app.images.sweep.enabled is off: the counts say what a sweep would delete. A takedown always deletes.",
        example = "true"
    )
    val deleted: Boolean
) {
    companion object {
        fun of(
            outcome: RemovalOutcome,
            deleted: Boolean
        ): ImageRemovalResponse = ImageRemovalResponse(outcome.images, outcome.objects, outcome.strays, deleted)
    }
}
