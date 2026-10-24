package de.norm.events.mail

/**
 * One mail to one recipient. The sender is not here: every mail leaves from `app.mail.from`.
 *
 * [kind] names what the mail is, such as `sign-in`. It tags the metric and the log line, so it is a
 * constant of the caller and never a value a visitor typed.
 */
data class MailMessage(
    val kind: String,
    val to: String,
    val subject: String,
    val text: String,
    val html: String? = null
) {
    /** The address stays out of `toString`, so a message logged or thrown by accident does not carry it. */
    override fun toString(): String = "MailMessage(kind=$kind, subject=$subject)"
}
