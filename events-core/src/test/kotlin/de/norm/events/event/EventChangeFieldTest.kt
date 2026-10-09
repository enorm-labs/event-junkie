package de.norm.events.event

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/** `event_change.field` refuses a name its CHECK does not list, so the enum and the migration name the same fields (#2725). */
class EventChangeFieldTest {
    @Test
    fun `names the fields the event_change CHECK allows`() {
        val migration = File("../events-importer/src/main/resources/db/migration/V133__create_event_change.sql").readText()
        val allowed =
            Regex("""field IN \(([^)]*)\)""")
                .find(migration)!!
                .groupValues[1]
                .split(",")
                .map { it.trim().trim('\'') }

        EventChangeField.entries.map { it.name } shouldBe allowed
    }
}
