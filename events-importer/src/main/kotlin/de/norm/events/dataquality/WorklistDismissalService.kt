package de.norm.events.dataquality

import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

/** Worklist rows the operator judged correct as they are (ADR-052). */
@Service
class WorklistDismissalService(
    private val worklistRepository: DataQualityWorklistRepository,
    private val dismissalRepository: WorklistDismissalRepository,
    private val clock: Clock = Clock.systemUTC()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Records that the operator judged one row correct as it is.
     *
     * The stored fingerprint is the one the database computes now. A [seen] fingerprint that differs means
     * a value changed after the page was read, and a dismissal then would hide a value nobody judged.
     */
    suspend fun dismiss(
        issue: QualityIssue,
        eventId: Long,
        seen: String
    ): WorklistDismissalResponse {
        val current =
            worklistRepository.currentFingerprint(issue, eventId)
                ?: throw StaleWorklistRowException("Event $eventId is not on the ${issue.key} worklist")
        if (current != seen) {
            throw StaleWorklistRowException("Event $eventId changed since the ${issue.key} worklist was read. Reload it and judge the row again")
        }
        val id = dismissalRepository.upsert(issue, eventId, current)
        logger.info { "Worklist row dismissed: ${issue.key} of event $eventId, dismissal $id" }
        return WorklistDismissalResponse.from(dismissalRepository.findByIssue(issue, id).single())
    }

    /** Undoes a dismissal, so its row is back on the worklist. */
    suspend fun undismiss(id: Long) {
        if (dismissalRepository.delete(id) == 0L) throw WorklistDismissalNotFoundException(id)
        logger.info { "Worklist dismissal $id undone" }
    }

    suspend fun dismissals(issue: QualityIssue): List<WorklistDismissalResponse> = dismissalRepository.findByIssue(issue).map(WorklistDismissalResponse::from)

    /** Deletes the dismissals of events that ended before today. A past row is nobody's work any more. */
    suspend fun purgePastDismissals(): Long = dismissalRepository.deleteForEventsBefore(LocalDate.now(clock))
}

/** Rendered as a 404. */
class WorklistDismissalNotFoundException(
    id: Long
) : RuntimeException("Worklist dismissal with id $id not found")

/** The row a dismissal names is gone from the worklist or shows other values now. Rendered as a 409. */
class StaleWorklistRowException(
    message: String
) : RuntimeException(message)
