package de.norm.events.common

import de.norm.events.event.EventType
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class AssumedStartTimeTest {
    // The table is keyed by name, so a renamed or added `EventType` fails here, not silently in SQL.
    @Test
    fun `the table names every event type`() {
        AssumedStartTime.TYPES shouldBe EventType.entries.map { it.name }.toSet()
    }

    @Test
    fun `a type the table does not name takes the slot of OTHER`() {
        AssumedStartTime.of("party") shouldBe LocalTime.of(23, 0)
        AssumedStartTime.of("SOMETHING_NEW") shouldBe AssumedStartTime.of("OTHER")
    }
}
