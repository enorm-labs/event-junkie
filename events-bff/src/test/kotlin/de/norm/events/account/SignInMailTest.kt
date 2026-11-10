package de.norm.events.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Pins both bodies of the sign-in mail. The origin and token carry every character HTML escapes. */
class SignInMailTest {
    private val origin = "https://example.de/a?b=1&c=<d>\"e'f"
    private val escapedOrigin = "https://example.de/a?b=1&amp;c=&lt;d&gt;&quot;e&#39;f"

    private fun message(locale: String) =
        SignInMail.message(
            to = "visitor@example.de",
            locale = locale,
            origin = origin,
            link = SignInMail.link(origin, locale, "t0k&n<>\"'", "/de/events"),
            validMinutes = 15
        )

    @Test
    fun `the German mail's plain-text body`() {
        message("de").text shouldBe
            "Hallo,\n\n" +
            "mit diesem Link meldest du dich bei Event Junkie an. Er gilt einmal und 15 Minuten lang:\n\n" +
            "$origin/de/sign-in/confirm?next=/de/events#token=t0k&n<>\"'\n\n" +
            "Wenn du ihn nicht angefordert hast, ignoriere diese Mail. Ohne den Link meldet sich niemand an, und es entsteht kein Konto.\n\n" +
            "Event Junkie\n" +
            "$origin\n"
    }

    @Test
    fun `the German mail's HTML body escapes the link and the origin`() {
        val link = "$escapedOrigin/de/sign-in/confirm?next=/de/events#token=t0k&amp;n&lt;&gt;&quot;&#39;"
        message("de").html shouldBe
            "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\"><p>Hallo,</p>\n" +
            "<p>mit diesem Link meldest du dich bei Event Junkie an. Er gilt einmal und 15 Minuten lang:</p>\n" +
            "<p><a href=\"$link\" style=\"display:inline-block;padding:10px 18px;background:#111;color:#fff;text-decoration:none\">Anmelden</a></p>" +
            "<p style=\"font-size:12px;color:#555\">$link</p>\n" +
            "<p>Wenn du ihn nicht angefordert hast, ignoriere diese Mail. Ohne den Link meldet sich niemand an, und es entsteht kein Konto.</p>" +
            "<p>Event Junkie<br>$escapedOrigin</p></body></html>"
    }

    @Test
    fun `the English mail's plain-text body`() {
        message("en").text shouldBe
            "Hello,\n\n" +
            "use this link to sign in to Event Junkie. It works once, for 15 minutes:\n\n" +
            "$origin/en/sign-in/confirm?next=/de/events#token=t0k&n<>\"'\n\n" +
            "If you did not ask for it, ignore this mail. Nobody signs in without the link, and no account is created.\n\n" +
            "Event Junkie\n" +
            "$origin\n"
    }

    @Test
    fun `the English mail's HTML body escapes the link and the origin`() {
        val link = "$escapedOrigin/en/sign-in/confirm?next=/de/events#token=t0k&amp;n&lt;&gt;&quot;&#39;"
        message("en").html shouldBe
            "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\"><p>Hello,</p>\n" +
            "<p>use this link to sign in to Event Junkie. It works once, for 15 minutes:</p>\n" +
            "<p><a href=\"$link\" style=\"display:inline-block;padding:10px 18px;background:#111;color:#fff;text-decoration:none\">Sign in</a></p>" +
            "<p style=\"font-size:12px;color:#555\">$link</p>\n" +
            "<p>If you did not ask for it, ignore this mail. Nobody signs in without the link, and no account is created.</p>" +
            "<p>Event Junkie<br>$escapedOrigin</p></body></html>"
    }
}
