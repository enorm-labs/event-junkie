package de.norm.events.account

import de.norm.events.mail.MailMessage
import org.springframework.web.util.HtmlUtils

/**
 * The sign-in mail, in German or English. The token travels in the fragment, so neither nginx's
 * access log nor a Referer ever carries it: the SPA reads it and POSTs it. A mail scanner that opens
 * the link therefore cannot use it up (#3887).
 */
internal object SignInMail {
    /** The confirm page's address. `next` is a checked path of letters, digits, hyphens and slashes (AuthController), so it needs no encoding. */
    fun link(
        origin: String,
        locale: String,
        token: String,
        next: String? = null
    ) = "$origin/$locale/sign-in/confirm${next?.let { "?next=$it" }.orEmpty()}#token=$token"

    fun message(
        to: String,
        locale: String,
        origin: String,
        link: String,
        validMinutes: Long
    ): MailMessage {
        val text = if (locale == "de") german(link, validMinutes) else english(link, validMinutes)
        return MailMessage(
            kind = KIND,
            to = to,
            subject = if (locale == "de") "Dein Anmeldelink für Event Junkie" else "Your sign-in link for Event Junkie",
            text = text.joinToString("\n\n") + "\n\nEvent Junkie\n$origin\n",
            html = html(text, link, if (locale == "de") "Anmelden" else "Sign in", origin)
        )
    }

    private fun german(
        link: String,
        minutes: Long
    ) = listOf(
        "Hallo,",
        "mit diesem Link meldest du dich bei Event Junkie an. Er gilt einmal und $minutes Minuten lang:",
        link,
        "Wenn du ihn nicht angefordert hast, ignoriere diese Mail. Ohne den Link meldet sich niemand an, und es entsteht kein Konto."
    )

    private fun english(
        link: String,
        minutes: Long
    ) = listOf(
        "Hello,",
        "use this link to sign in to Event Junkie. It works once, for $minutes minutes:",
        link,
        "If you did not ask for it, ignore this mail. Nobody signs in without the link, and no account is created."
    )

    private fun html(
        paragraphs: List<String>,
        link: String,
        button: String,
        origin: String
    ): String {
        val escapedLink = HtmlUtils.htmlEscape(link)
        val body =
            paragraphs.joinToString("\n") { paragraph ->
                if (paragraph == link) {
                    "<p><a href=\"$escapedLink\" style=\"display:inline-block;padding:10px 18px;background:#111;color:#fff;text-decoration:none\">" +
                        "${HtmlUtils.htmlEscape(button)}</a></p><p style=\"font-size:12px;color:#555\">$escapedLink</p>"
                } else {
                    "<p>${HtmlUtils.htmlEscape(paragraph)}</p>"
                }
            }
        return "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\">$body" +
            "<p>Event Junkie<br>${HtmlUtils.htmlEscape(origin)}</p></body></html>"
    }

    const val KIND = "sign-in"
}
