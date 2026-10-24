package de.norm.events.account

import de.norm.events.mail.MailMessage
import org.springframework.web.util.HtmlUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * The mail a month before an inactive account is deleted (#3905), in the account's language. It links
 * to the sign-in page, not to a sign-in: a link that signs in would sit in an old inbox for a month.
 */
internal object DeletionWarningMail {
    fun message(
        account: Account,
        origin: String,
        lastSignIn: LocalDate,
        deletedFrom: LocalDate
    ): MailMessage {
        val german = account.locale == "de"
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(if (german) Locale.GERMAN else Locale.ENGLISH)
        val link = "$origin/${account.locale}/sign-in"
        val text =
            if (german) {
                german(format.format(lastSignIn), format.format(deletedFrom), link)
            } else {
                english(format.format(lastSignIn), format.format(deletedFrom), link)
            }
        return MailMessage(
            kind = KIND,
            to = account.email,
            subject = if (german) "Dein Konto bei Event Junkie wird gelöscht" else "Your Event Junkie account will be deleted",
            text = text.joinToString("\n\n") + "\n\nEvent Junkie\n$origin\n",
            html = html(text, link, origin)
        )
    }

    private fun german(
        lastSignIn: String,
        deletedFrom: String,
        link: String
    ) = listOf(
        "Hallo,",
        "du hast dich seit dem $lastSignIn nicht mehr bei Event Junkie angemeldet. Nach 24 Monaten ohne Anmeldung lösche ich ein Konto. " +
            "Dein Konto lösche ich ab dem $deletedFrom.",
        "Wenn du es behalten willst, melde dich vorher einmal an:",
        link,
        "Sonst musst du nichts tun."
    )

    private fun english(
        lastSignIn: String,
        deletedFrom: String,
        link: String
    ) = listOf(
        "Hello,",
        "you have not signed in to Event Junkie since $lastSignIn. I delete an account after 24 months without a sign-in. " +
            "I will delete yours on or after $deletedFrom.",
        "To keep it, sign in once before then:",
        link,
        "Otherwise you need to do nothing."
    )

    private fun html(
        paragraphs: List<String>,
        link: String,
        origin: String
    ): String {
        val escapedLink = HtmlUtils.htmlEscape(link)
        val body =
            paragraphs.joinToString("\n") { paragraph ->
                if (paragraph == link) {
                    "<p><a href=\"$escapedLink\">$escapedLink</a></p>"
                } else {
                    "<p>${HtmlUtils.htmlEscape(paragraph)}</p>"
                }
            }
        return "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\">$body" +
            "<p>Event Junkie<br>${HtmlUtils.htmlEscape(origin)}</p></body></html>"
    }

    const val KIND = "deletion-warning"
}
