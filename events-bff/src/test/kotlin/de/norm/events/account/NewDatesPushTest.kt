package de.norm.events.account

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class NewDatesPushTest {
    private val account = UUID.randomUUID()

    private fun date(
        title: String,
        locale: String = "en",
        slug: String = "x"
    ) = NewDate(account, locale, slug, title, LocalDate.of(2026, 11, 14))

    @Test
    fun `one date links to the event`() {
        val message = NewDatesPush.message(listOf(date("Moderat", slug = "moderat-2026-11-14")))

        message.title shouldBe "New date"
        message.body shouldBe "Moderat · Sat 14 Nov"
        message.url shouldBe "/en/events/moderat-2026-11-14"
        message.tag shouldBe "new-dates"
    }

    @Test
    fun `several dates are one push that links to Following, in the account's language`() {
        val message = NewDatesPush.message(listOf(date("Moderat", "de"), date("Apparat", "de")))

        message.title shouldBe "2 neue Termine"
        message.body shouldBe "Moderat, Apparat"
        message.url shouldBe "/de/following"
        message.tag shouldBe "new-dates"
    }

    @Test
    fun `a long list is cut to one line`() {
        val message = NewDatesPush.message((1..30).map { date("Night number $it") })

        (message.body.length <= 120) shouldBe true
        message.body shouldEndWith "…"
    }
}
