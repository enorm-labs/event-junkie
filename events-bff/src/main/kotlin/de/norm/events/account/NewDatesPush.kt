package de.norm.events.account

import de.norm.events.push.PushMessage
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The text of a push about new dates (#3894), in the account's language. One date links to the event, several to
 * Following, so a whole tour imported at once is one push. Only what ADR-055 rule 3 allows: a title, one line, a link.
 */
object NewDatesPush {
    private const val MAX_BODY = 120
    private val germanDate = DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN)
    private val englishDate = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

    fun message(dates: List<NewDate>): PushMessage {
        val first = dates.first()
        val german = first.locale == "de"
        val format = if (german) germanDate else englishDate
        return if (dates.size == 1) {
            PushMessage(
                title = if (german) "Neuer Termin" else "New date",
                body = "${first.title} · ${first.date.format(format)}",
                url = "/${first.locale}/events/${first.slug}"
            )
        } else {
            PushMessage(
                title = if (german) "${dates.size} neue Termine" else "${dates.size} new dates",
                body = shorten(dates.joinToString(", ") { it.title }),
                url = "/${first.locale}/following"
            )
        }
    }

    private fun shorten(text: String): String = if (text.length <= MAX_BODY) text else text.take(MAX_BODY - 1).trimEnd(' ', ',') + "…"
}
