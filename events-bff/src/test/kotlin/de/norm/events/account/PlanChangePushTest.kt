package de.norm.events.account

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class PlanChangePushTest {
    private val account = UUID.randomUUID()

    private fun due(
        title: String,
        change: PlanChange,
        locale: String = "en"
    ) = PlanChangeDue(account, locale, title.lowercase(), title, LocalDate.of(2026, 11, 6), change)

    @Test
    fun `one change names it and links to the event`() {
        val message = PlanChangePush.message(listOf(due("Moderat", PlanChange.CANCELLED)))

        message.title shouldBe "Cancelled"
        message.body shouldBe "Moderat · Fri 6 Nov"
        message.url shouldBe "/en/events/moderat"
        message.tag shouldBe "plan-changes"
    }

    @Test
    fun `German, and several changes link to my calendar`() {
        PlanChangePush.message(listOf(due("Moderat", PlanChange.TIME, "de"))).title shouldBe "Neue Uhrzeit"

        val message = PlanChangePush.message(listOf(due("Moderat", PlanChange.DATE, "de"), due("Kraftklub", PlanChange.VENUE, "de")))

        message.title shouldBe "2 Änderungen an deinen Plänen"
        message.body shouldBe "Moderat, Kraftklub"
        message.url shouldBe "/de/my-calendar"
        message.tag shouldBe "plan-changes"
    }
}
