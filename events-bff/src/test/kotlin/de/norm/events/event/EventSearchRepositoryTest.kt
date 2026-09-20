package de.norm.events.event

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * The seeded tiebreak (#1380): rows that tie on date and start time are ordered by
 * `md5(id || today)`. The repository is built by hand with a fixed [Clock], the only way to hold
 * two different days against the same rows.
 */
class EventSearchRepositoryTest : BaseControllerTest() {
    private fun repositoryOn(day: LocalDate) = EventSearchRepository(databaseClient, Clock.fixed(day.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC))

    private val night: LocalDate = LocalDate.of(2030, 6, 15)

    /** Eight events with the same date and doors, so the tiebreak is the only order left. */
    private suspend fun seedTiedNight(): List<Long> {
        val venueId = insertVenue("Astra", "astra")
        return (1..8).map { insertEvent(venueId, "Night $it", "night-$it", night, startTime = LocalTime.of(23, 0)) }
    }

    /** The rule the SQL claims to apply, computed off-database so the assertion is not circular. */
    private fun expectedOrder(
        ids: List<Long>,
        seed: LocalDate
    ): List<Long> = ids.sortedWith(compareBy<Long> { md5Hex("$it$seed") }.thenBy { it })

    private fun md5Hex(text: String): String = MessageDigest.getInstance("MD5").digest(text.toByteArray()).toHexString()

    @Test
    fun `orders a tie by the md5 of id and day, then by id`(): Unit =
        runBlocking {
            val ids = seedTiedNight()
            val day = LocalDate.of(2030, 6, 1)

            repositoryOn(day).searchAll(EventFilter(from = night, to = night)) shouldBe expectedOrder(ids, day)
        }

    @Test
    fun `the same day gives the same order twice`(): Unit =
        runBlocking {
            seedTiedNight()
            val repository = repositoryOn(LocalDate.of(2030, 6, 1))
            val filter = EventFilter(from = night, to = night)

            repository.searchAll(filter) shouldBe repository.searchAll(filter)
        }

    @Test
    fun `two days give two orders for the same tie`(): Unit =
        runBlocking {
            val ids = seedTiedNight()
            // Pick the pair off-database, so the test never depends on which ids the sequence handed out.
            val first = LocalDate.of(2030, 6, 1)
            val second = generateSequence(first.plusDays(1)) { it.plusDays(1) }.first { expectedOrder(ids, it) != expectedOrder(ids, first) }
            val filter = EventFilter(from = night, to = night)

            val firstOrder = repositoryOn(first).searchAll(filter)
            val secondOrder = repositoryOn(second).searchAll(filter)

            firstOrder shouldNotBe secondOrder
            firstOrder.sorted() shouldBe secondOrder.sorted()
        }

    @Test
    fun `pages follow each other within a day`(): Unit =
        runBlocking {
            seedTiedNight()
            val repository = repositoryOn(LocalDate.of(2030, 6, 1))
            val filter = EventFilter(from = night, to = night)

            val paged = (0..2).flatMap { page -> repository.search(filter, PageRequest.of(page, 3)).ids }

            paged shouldBe repository.searchAll(filter)
        }

    @Test
    fun `an explicit sort still ends in the seeded tiebreak`(): Unit =
        runBlocking {
            val ids = seedTiedNight()
            val day = LocalDate.of(2030, 6, 1)
            val pageable =
                PageRequest.of(
                    0,
                    8,
                    org.springframework.data.domain.Sort
                        .by("eventDate")
                )

            repositoryOn(day).search(EventFilter(from = night, to = night), pageable).ids shouldBe expectedOrder(ids, day)
        }

    /**
     * A timeless event sorts into the slot its kind usually takes (#1384): doors when it has them,
     * else [AssumedStartTime]'s table. Also the test that the SQL rendering agrees with the Kotlin.
     */
    @Test
    fun `a timeless event sorts by its doors, else by the slot its kind usually takes`(): Unit =
        runBlocking {
            val venueId = insertVenue("Astra", "astra")
            val exhibition = insertEvent(venueId, "Run", "run", night, eventType = "EXHIBITION")
            val doorsOnly = insertEvent(venueId, "Doors", "doors", night, doorsTime = LocalTime.of(19, 0))
            val concert = insertEvent(venueId, "Gig", "gig", night, startTime = LocalTime.of(20, 0))
            val party = insertEvent(venueId, "Night", "night", night, eventType = "PARTY")
            val late = insertEvent(venueId, "Late", "late", night, startTime = LocalTime.of(23, 30))
            val filter = EventFilter(from = night, to = night)
            val repository = repositoryOn(LocalDate.of(2030, 6, 1))

            val expected = listOf(exhibition, doorsOnly, concert, party, late)
            repository.searchAll(filter) shouldBe expected
            // `sort=startTime` maps to the same expression, or a sort by time would sink the same rows.
            repository.search(filter, PageRequest.of(0, 5, Sort.by("startTime"))).ids shouldBe expected
        }

    /**
     * The window an event is listed in once it has an end (ADR-029): the default window keeps a
     * weekender through its last day, `from` means "starts on or after", `on` is the Tonight case.
     */
    @Test
    fun `a weekender stays in the default window until its end, and is on every day of its span`(): Unit =
        runBlocking {
            val venueId = insertVenue("Sisyphos", "sisyphos")
            val friday = LocalDate.of(2030, 6, 14)
            val weekender = insertEvent(venueId, "Weekender", "weekender", friday, endDate = friday.plusDays(3))
            val tuesdayNight = insertEvent(venueId, "Tuesday", "tuesday", friday.plusDays(4))

            // Saturday: the default window still has the weekender; "from Sunday" does not, it started Friday.
            repositoryOn(friday.plusDays(1)).searchAll(EventFilter()) shouldBe listOf(weekender, tuesdayNight)
            repositoryOn(friday.plusDays(1)).searchAll(EventFilter(from = friday.plusDays(2))) shouldBe listOf(tuesdayNight)
            // Tuesday: over.
            repositoryOn(friday.plusDays(4)).searchAll(EventFilter()) shouldBe listOf(tuesdayNight)

            // Tonight on Sunday is the weekender alone; on Tuesday, the Tuesday night alone.
            repositoryOn(friday).searchAll(EventFilter(on = friday.plusDays(2))) shouldBe listOf(weekender)
            repositoryOn(friday).searchAll(EventFilter(on = friday.plusDays(4))) shouldBe listOf(tuesdayNight)
        }

    /**
     * The late-night grace (#299): before 06:00, last night's event with no stated end and a late
     * effective start is still on, for the default window and Tonight alike. A 20:00 concert is over
     * at midnight, a stated end is the venue's word, and at 06:00 the night is over.
     */
    @Test
    fun `a late night without an end is still on before six the next morning`(): Unit =
        runBlocking {
            val venueId = insertVenue("Tresor", "tresor")
            val friday = LocalDate.of(2030, 6, 14)
            val saturday = friday.plusDays(1)
            val club = insertEvent(venueId, "Club", "club", friday, startTime = LocalTime.of(23, 0))
            // No time at all: the #1384 PARTY slot is 23:00, so it counts as a night.
            val timeless = insertEvent(venueId, "Timeless", "timeless", friday, eventType = "PARTY")
            insertEvent(venueId, "Gig", "gig", friday, startTime = LocalTime.of(20, 0))
            insertEvent(venueId, "Ended", "ended", friday, startTime = LocalTime.of(23, 0), endDate = friday, endTime = LocalTime.of(23, 59))
            val saturdayNight = insertEvent(venueId, "Next", "next", saturday, startTime = LocalTime.of(23, 0))

            fun at(hour: Int) = EventSearchRepository(databaseClient, Clock.fixed(saturday.atTime(hour, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))

            // The club and the timeless night tie at 23:00 and rotate by the seed, so the order is not the point.
            at(3).searchAll(EventFilter()) shouldContainExactlyInAnyOrder listOf(club, timeless, saturdayNight)
            at(3).searchAll(EventFilter(on = saturday)) shouldContainExactlyInAnyOrder listOf(club, timeless, saturdayNight)
            at(7).searchAll(EventFilter()) shouldBe listOf(saturdayNight)
            at(7).searchAll(EventFilter(on = saturday)) shouldBe listOf(saturdayNight)
            // Tonight for a day that is not the clock's own gets no grace: Sunday at 03:00 asks about Sunday.
            at(3).searchAll(EventFilter(on = saturday.plusDays(1))) shouldBe emptyList()
        }

    /**
     * A stated end on the clock's own day is over once the clock passes it (ADR-029): a night ending
     * at 04:00 leaves the window at 04:00. A run ending today with no time stays all day.
     */
    @Test
    fun `an event that ended this morning is not listed at noon`(): Unit =
        runBlocking {
            val venueId = insertVenue("Klunkerkranich", "klunkerkranich")
            val friday = LocalDate.of(2030, 6, 14)
            val saturday = friday.plusDays(1)
            val night = insertEvent(venueId, "Night", "night", friday, startTime = LocalTime.of(19, 0), endDate = saturday, endTime = LocalTime.of(4, 0))
            val run = insertEvent(venueId, "Run", "run", friday, endDate = saturday)
            val brunch = insertEvent(venueId, "Brunch", "brunch", saturday, startTime = LocalTime.of(11, 0), endDate = saturday, endTime = LocalTime.of(15, 0))
            val saturdayNight = insertEvent(venueId, "Next", "next", saturday, startTime = LocalTime.of(23, 0))

            fun at(hour: Int) = EventSearchRepository(databaseClient, Clock.fixed(saturday.atTime(hour, 0).toInstant(ZoneOffset.UTC), ZoneOffset.UTC))

            at(3).searchAll(EventFilter()) shouldContainExactlyInAnyOrder listOf(night, run, brunch, saturdayNight)
            at(12).searchAll(EventFilter()) shouldContainExactlyInAnyOrder listOf(run, brunch, saturdayNight)
            at(12).searchAll(EventFilter(on = saturday)) shouldContainExactlyInAnyOrder listOf(run, brunch, saturdayNight)
            at(16).searchAll(EventFilter()) shouldContainExactlyInAnyOrder listOf(run, saturdayNight)
            // Asked about today from another day, the whole end date counts.
            repositoryOn(friday).searchAll(EventFilter(on = saturday)) shouldContainExactlyInAnyOrder listOf(night, run, brunch, saturdayNight)
        }
}
