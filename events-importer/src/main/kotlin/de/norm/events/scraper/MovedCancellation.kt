package de.norm.events.scraper

import de.norm.events.event.EventStatus
import java.time.LocalDate

/**
 * Decides whether a `CANCELLED` row only moved its date: Tempodrom's badge says cancelled beside
 * "Verschoben auf den 31.05.2027", Wühlmäuse's beside "VERLEGT auf den 24.04.2027". A note with
 * `verlegt`, `verschoben` or `verschiebt` followed, in the same sentence, by a date after
 * [eventDate] makes the row `POSTPONED`, so the page can link the new date's row. Any other status
 * passes through (#4268).
 *
 * The date is required because the word alone does not say what moved. `verlegt` also means a change
 * of house, which is why #3576 declined a word-only rule. A bare "VERLEGT!" or "verlegt ins Huxleys"
 * stays `CANCELLED`, and so does a show merged into a date it already had ("verschoben und mit dem
 * Termin am 16.06.2027 zusammengelegt"), which names no date after "auf".
 */
fun resolveMovedCancellation(
    status: String,
    notes: List<String>,
    eventDate: LocalDate
): String {
    if (status != EventStatus.CANCELLED.name) return status
    val moved =
        notes.any { note ->
            MOVED_TO_DATE.findAll(note).any { targetDate(it)?.isAfter(eventDate) == true }
        }
    return if (moved) EventStatus.POSTPONED.name else status
}

/**
 * A move word and the new date after it, in one sentence: "VERLEGT auf den 24.04.2027", "verlegt
 * werden muss auf den 24.04.2027", "Der 05.11.2026 verschiebt sich auf den 04.11.2027". The gap
 * holds no sentence end, so a date in the next sentence is not read as this one's.
 */
private val MOVED_TO_DATE =
    Regex("""(?:verlegt|verschoben|verschiebt)\b[^.!?\n]{0,80}?\bauf\s+(?:den\s+)?(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})(?!\d)""", RegexOption.IGNORE_CASE)
