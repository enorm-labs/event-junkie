package de.norm.events.event

import jakarta.validation.Validator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.server.ServerWebInputException

/** `PATCH /api/admin/events/{id}` (#3995): a patch merged onto the stored event and saved as a PUT. */
@Service
class EventPatchService(
    private val eventService: EventService,
    private val validator: Validator
) {
    /**
     * Applies [patch] to the stored event and saves it through [EventService.update], so pins, slug and
     * change log follow the PUT's rules. The merged event is validated as a PUT body is: a blank title is a 400.
     *
     * @throws EventNotFoundException if no event with the given [id] exists.
     */
    @Transactional
    suspend fun patch(
        id: Long,
        patch: EventPatchRequest
    ): EventResponse {
        val merged = patch.mergedOnto(eventService.findById(id))
        val violations = validator.validate(merged)
        if (violations.isNotEmpty()) {
            throw ServerWebInputException(violations.joinToString("; ") { "${it.propertyPath}: ${it.message}" })
        }
        return eventService.update(id, merged)
    }
}
