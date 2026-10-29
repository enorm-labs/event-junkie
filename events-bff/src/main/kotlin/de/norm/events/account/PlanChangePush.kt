package de.norm.events.account

import de.norm.events.push.PushMessage
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The text of a push about a changed event the account marked (#4045), in the account's language. One change links
 * to the event, several to My calendar. Only what ADR-055 rule 3 allows: a title, one line, a link
 * and the kind.
 */
object PlanChangePush {
    const val TAG = "plan-changes"
    private const val MAX_BODY = 120
    private val germanDate = DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN)
    private val englishDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun message(changes: List<PlanChangeDue>): PushMessage {
        val first = changes.first()
        val german = first.locale == "de"
        val format = if (german) germanDate else englishDate
        return if (changes.size == 1) {
            PushMessage(
                title = title(first.change, german),
                body = "${first.title} · ${first.date.format(format)}",
                url = "/${first.locale}/events/${first.slug}",
                tag = TAG
            )
        } else {
            PushMessage(
                title = if (german) "${changes.size} Änderungen an deinen Plänen" else "${changes.size} changes to your plans",
                body = shorten(changes.joinToString(", ") { it.title }),
                url = "/${first.locale}/my-calendar",
                tag = TAG
            )
        }
    }

    private fun title(
        change: PlanChange,
        german: Boolean
    ): String =
        when (change) {
            PlanChange.CANCELLED -> if (german) "Abgesagt" else "Cancelled"
            PlanChange.POSTPONED -> if (german) "Verschoben" else "Postponed"
            PlanChange.RELOCATED -> if (german) "Verlegt" else "Relocated"
            PlanChange.DATE -> if (german) "Neues Datum" else "Date changed"
            PlanChange.TIME -> if (german) "Neue Uhrzeit" else "Time changed"
            PlanChange.VENUE -> if (german) "Neuer Ort" else "Venue changed"
        }

    private fun shorten(text: String): String = if (text.length <= MAX_BODY) text else text.take(MAX_BODY - 1).trimEnd(' ', ',') + "…"
}
