package de.norm.events.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Pins both bodies of the deletion warning. The origin carries every character HTML escapes, the German dates an umlaut. */
class DeletionWarningMailTest {
    private val origin = "https://example.de/a?b=1&c=<d>\"e'f"
    private val escapedOrigin = "https://example.de/a?b=1&amp;c=&lt;d&gt;&quot;e&#39;f"

    private fun message(locale: String) =
        DeletionWarningMail.message(
            account = Account(UUID.fromString("00000000-0000-0000-0000-000000000001"), "visitor@example.de", locale, Instant.EPOCH, Instant.EPOCH),
            origin = origin,
            lastSignIn = LocalDate.of(2024, 3, 1),
            deletedFrom = LocalDate.of(2026, 3, 1)
        )

    @Test
    fun `the German mail's plain-text body`() {
        message("de").text shouldBe
            "Hallo,\n\n" +
            "du hast dein Konto bei Event Junkie seit dem 1. März 2024 nicht mehr genutzt. Konten werden nach 24 Monaten ohne Nutzung " +
            "automatisch gelöscht, deins ab dem 1. März 2026.\n\n" +
            "Wenn du dein Konto behalten willst, melde dich vorher einmal an:\n\n" +
            "$origin/de/sign-in\n\n" +
            "Wenn du es nicht mehr brauchst, musst du nichts tun.\n\n" +
            "Event Junkie\n" +
            "$origin\n"
    }

    @Test
    fun `the German mail's HTML body escapes the link, the origin and the umlauts`() {
        val link = "$escapedOrigin/de/sign-in"
        message("de").html shouldBe
            "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\"><p>Hallo,</p>\n" +
            "<p>du hast dein Konto bei Event Junkie seit dem 1. M&auml;rz 2024 nicht mehr genutzt. Konten werden nach 24 Monaten ohne Nutzung " +
            "automatisch gel&ouml;scht, deins ab dem 1. M&auml;rz 2026.</p>\n" +
            "<p>Wenn du dein Konto behalten willst, melde dich vorher einmal an:</p>\n" +
            "<p><a href=\"$link\">$link</a></p>\n" +
            "<p>Wenn du es nicht mehr brauchst, musst du nichts tun.</p>" +
            "<p>Event Junkie<br>$escapedOrigin</p></body></html>"
    }

    @Test
    fun `the English mail's plain-text body`() {
        message("en").text shouldBe
            "Hello,\n\n" +
            "you have not used your Event Junkie account since 1 March 2024. Accounts are deleted automatically after 24 months without " +
            "use, and yours will be deleted on or after 1 March 2026.\n\n" +
            "To keep your account, sign in once before then:\n\n" +
            "$origin/en/sign-in\n\n" +
            "If you no longer need it, you do not have to do anything.\n\n" +
            "Event Junkie\n" +
            "$origin\n"
    }

    @Test
    fun `the English mail's HTML body escapes the link and the origin`() {
        val link = "$escapedOrigin/en/sign-in"
        message("en").html shouldBe
            "<!doctype html><html><body style=\"font-family:sans-serif;line-height:1.5\"><p>Hello,</p>\n" +
            "<p>you have not used your Event Junkie account since 1 March 2024. Accounts are deleted automatically after 24 months without " +
            "use, and yours will be deleted on or after 1 March 2026.</p>\n" +
            "<p>To keep your account, sign in once before then:</p>\n" +
            "<p><a href=\"$link\">$link</a></p>\n" +
            "<p>If you no longer need it, you do not have to do anything.</p>" +
            "<p>Event Junkie<br>$escapedOrigin</p></body></html>"
    }
}
