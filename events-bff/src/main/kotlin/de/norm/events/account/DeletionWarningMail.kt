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
        val format = DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(if (german) Locale.GERMAN else Locale.UK)
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
        "du hast dein Konto bei Event Junkie seit dem $lastSignIn nicht mehr genutzt. Konten werden nach 24 Monaten ohne Nutzung " +
            "automatisch gelöscht, deins ab dem $deletedFrom.",
        "Wenn du dein Konto behalten willst, melde dich vorher einmal an:",
        link,
        "Wenn du es nicht mehr brauchst, musst du nichts tun."
    )

    private fun english(
        lastSignIn: String,
        deletedFrom: String,
        link: String
    ) = listOf(
        "Hello,",
        "you have not used your Event Junkie account since $lastSignIn. Accounts are deleted automatically after 24 months without " +
            "use, and yours will be deleted on or after $deletedFrom.",
        "To keep your account, sign in once before then:",
        link,
        "If you no longer need it, you do not have to do anything."
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
