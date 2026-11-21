package de.norm.events.event

/**
 * Work that an admin create or edit runs on the saved event, owned by a module that `event` may not depend on.
 * [EventService] calls every bean in the same transaction, after the save. The import pipeline implements it to read the
 * event features off the text, as an import does (#5334).
 */
fun interface EventSavedByHand {
    suspend fun afterSave(event: EventEntity)
}
