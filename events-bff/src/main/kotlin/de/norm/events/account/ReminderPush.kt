package de.norm.events.account

import de.norm.events.push.PushMessage

/**
 * The text of the day reminder (#4253), in the account's language. One event links to the event, several to My calendar.
 * German has no "at" that fits every venue's article, so it sets the place apart with a dot. Only what ADR-055 rule 3
 * allows: a title, one line, a link and the kind.
 */
object ReminderPush {
    /** One tag for every reminder, so a second send replaces the first on the device (#4167). */
    const val TAG = "reminder"
    private const val MAX_BODY = 120

    fun message(events: List<ReminderDue>): PushMessage {
        val first = events.first()
        val german = first.locale == "de"
        val named = if (german) "${first.title} · ${first.place}" else "${first.title} at ${first.place}"
        return if (events.size == 1) {
            PushMessage(
                title = if (german) "Heute Abend: $named" else "Tonight: $named",
                body = if (german) "Dein Plan für heute" else "Your plan for today",
                url = "/${first.locale}/events/${first.slug}",
                tag = TAG
            )
        } else {
            PushMessage(
                title = if (german) "Heute: ${events.size} deiner Events, zuerst $named" else "Today: ${events.size} of your events, first $named",
                body = shorten(events.joinToString(", ") { it.title }),
                url = "/${first.locale}/my-calendar",
                tag = TAG
            )
        }
    }

    private fun shorten(text: String): String = if (text.length <= MAX_BODY) text else text.take(MAX_BODY - 1).trimEnd(' ', ',') + "…"
}
