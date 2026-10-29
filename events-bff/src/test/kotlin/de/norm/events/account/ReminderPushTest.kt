package de.norm.events.account

import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class ReminderPushTest {
    private val account = UUID.randomUUID()

    private fun due(
        title: String,
        place: String,
        locale: String = "en"
    ) = ReminderDue(account, locale, title.lowercase(), title, place)

    @Test
    fun `one event names it and its venue, and links to the event`() {
        val message = ReminderPush.message(listOf(due("Moderat", "Columbiahalle")))

        message.title shouldBe "Tonight: Moderat at Columbiahalle"
        message.url shouldBe "/en/events/moderat"
        message.tag shouldBe "reminder"
    }

    @Test
    fun `several events count them, name the first and link to my calendar`() {
        val message = ReminderPush.message(listOf(due("Moderat", "Columbiahalle"), due("Kraftklub", "Astra"), due("Sleaford Mods", "SO36")))

        message.title shouldBe "Today: 3 of your events, first Moderat at Columbiahalle"
        message.body shouldBe "Moderat, Kraftklub, Sleaford Mods"
        message.url shouldBe "/en/my-calendar"
        message.tag shouldBe "reminder"
    }

    @Test
    fun `German sets the place apart with a dot`() {
        ReminderPush.message(listOf(due("Moderat", "Columbiahalle", "de"))).title shouldBe "Heute Abend: Moderat · Columbiahalle"
        val several = ReminderPush.message(listOf(due("Moderat", "Columbiahalle", "de"), due("Kraftklub", "Astra", "de")))
        several.title shouldBe "Heute: 2 deiner Events, zuerst Moderat · Columbiahalle"
        several.url shouldBe "/de/my-calendar"
    }

    @Test
    fun `a long list is cut to one line`() {
        val message = ReminderPush.message((1..20).map { due("A rather long event title $it", "Club") })

        message.body.length shouldBeLessThanOrEqual 120
        message.body.endsWith("…") shouldBe true
    }
}
