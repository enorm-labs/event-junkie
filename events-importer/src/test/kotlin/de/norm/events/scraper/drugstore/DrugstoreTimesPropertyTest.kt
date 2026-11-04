package de.norm.events.scraper.drugstore

import de.norm.events.scraper.radar.RadarGroupImporter.Times
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalTime

/** Properties of [parseDrugstoreTimes]: `DrugstoreTimesTest` pins the examples. */
class DrugstoreTimesPropertyTest {
    private val clock = Arb.bind(Arb.int(0..23), Arb.element(0, 15, 30, 45)) { hour, minute -> LocalTime.of(hour, minute) }

    @Test
    fun `any two labelled clocks give doors before the start, or radar's time alone`() =
        runTest {
            checkAll(clock, clock, clock) { doors, start, radarTime ->
                val times = parseDrugstoreTimes("Doors: $doors\nStart: $start", radarTime)

                times shouldBe
                    when {
                        doors < start -> Times(start = start, doors = doors)
                        doors == start -> Times(start = start)
                        else -> Times(start = radarTime)
                    }
            }
        }

    @Test
    fun `any two bare hours past noon give doors before the start, or radar's time alone`() =
        runTest {
            checkAll(Arb.int(13..23), Arb.int(13..23), clock) { doors, start, radarTime ->
                val times = parseDrugstoreTimes("Doors $doors | Start $start", radarTime)

                times shouldBe
                    when {
                        doors < start -> Times(start = LocalTime.of(start, 0), doors = LocalTime.of(doors, 0))
                        doors == start -> Times(start = LocalTime.of(start, 0))
                        else -> Times(start = radarTime)
                    }
            }
        }

    @Test
    fun `a text with no label keeps radar's time`() =
        runTest {
            checkAll(Arb.string(), clock) { text, radarTime ->
                parseDrugstoreTimes(text.replace(":", ""), radarTime) shouldBe Times(start = radarTime)
            }
        }

    @Test
    fun `the letter case of a text does not change what it says`() =
        runTest {
            checkAll(clock, clock, clock) { doors, start, radarTime ->
                val text = "Doors: $doors\nStart: $start"

                parseDrugstoreTimes(text.uppercase(), radarTime) shouldBe parseDrugstoreTimes(text, radarTime)
            }
        }
}
