package de.norm.events.scraper.drugstore

import de.norm.events.scraper.radar.RadarGroupImporter.Times
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.LocalTime

class DrugstoreTimesTest {
    private val radarTime = LocalTime.of(19, 0)
    private val unread = Times(start = radarTime)

    private fun times(description: String?) = parseDrugstoreTimes(description, radarTime)

    @Test
    fun `both labels give the start and the doors, in the words radar prints`() {
        times("Doors: 7pm\nStart: 8:30pm") shouldBe Times(start = LocalTime.of(20, 30), doors = LocalTime.of(19, 0))
        times("Doors: 7pm\nStart: 8:00pm") shouldBe Times(start = LocalTime.of(20, 0), doors = LocalTime.of(19, 0))
        parseDrugstoreTimes("Doors: 5pm\nStart: 6pm", LocalTime.of(17, 0)) shouldBe Times(start = LocalTime.of(18, 0), doors = LocalTime.of(17, 0))
    }

    @Test
    fun `a text with neither label keeps radar's time`() {
        times("D.I.Y. Underground Indoor Festival\nFree Entry") shouldBe unread
        times(null) shouldBe unread
    }

    @Test
    fun `doors alone stay beside radar's time as the start`() {
        parseDrugstoreTimes("Doors: 7pm", LocalTime.of(20, 0)) shouldBe Times(start = LocalTime.of(20, 0), doors = LocalTime.of(19, 0))
    }

    @Test
    fun `doors alone at radar's time leave the row its one time`() {
        times("Doors: 7pm") shouldBe unread
    }

    @Test
    fun `doors alone after radar's time are not read`() {
        times("Doors: 9pm") shouldBe unread
    }

    @Test
    fun `equal labels give a start without doors`() {
        times("Doors: 8pm\nStart: 8pm") shouldBe Times(start = LocalTime.of(20, 0))
    }

    @Test
    fun `a start alone is not read`() {
        times("Start: 8:30pm") shouldBe unread
    }

    @Test
    fun `a start before the doors keeps radar's time, as over midnight the row cannot hold both`() {
        parseDrugstoreTimes("Doors: 11pm\nStart: 1am", LocalTime.of(23, 0)) shouldBe Times(start = LocalTime.of(23, 0))
        times("Doors: 8pm\nStart: 7pm") shouldBe unread
    }

    @Test
    fun `a night that runs past midnight reads its labels as usual`() {
        times("Doors: 10pm\nStart: 11pm\nOpen until 6am") shouldBe Times(start = LocalTime.of(23, 0), doors = LocalTime.of(22, 0))
    }

    @Test
    fun `a text that labels several nights is not read`() {
        times("Friday Doors: 7pm Start: 8pm\nSaturday Doors: 5pm Start: 6pm") shouldBe unread
        times("Doors: 7pm\nFriday Start: 8pm\nSaturday Start: 9pm") shouldBe unread
    }

    @Test
    fun `labels read in any case and on one line`() {
        val expected = Times(start = LocalTime.of(20, 30), doors = LocalTime.of(19, 0))
        times("DOORS: 7PM START: 8:30PM") shouldBe expected
        times("doors: 7pm | start: 8:30pm") shouldBe expected
    }

    @Test
    fun `a non-breaking space after a label or a clock does not hide it`() {
        times("Doors:\u00A07pm\u00A0\nStart:\u00A08:30pm") shouldBe Times(start = LocalTime.of(20, 30), doors = LocalTime.of(19, 0))
    }

    @Test
    fun `a 24-hour clock reads too`() {
        times("Doors: 19:00\nStart: 20:30") shouldBe Times(start = LocalTime.of(20, 30), doors = LocalTime.of(19, 0))
    }

    @Test
    fun `a word that only ends in the label and a label without a clock are not read`() {
        times("Indoors: 7pm") shouldBe unread
        times("Doors: soon\nStart: 8pm") shouldBe unread
    }

    @Test
    fun `the labels without a colon are not this reader's shape`() {
        times("Doors 7pm | Start 8pm") shouldBe unread
    }
}
