package de.norm.events.event

import de.norm.events.BaseControllerTest
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
 * `md5(id || today)`, so the order is the same for everyone all day and different tomorrow.
 *
 * The repository is built by hand with a fixed [Clock], which is the only way to hold two
 * different days against the same rows.
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
     * else [AssumedStartTime]'s table. The sort and the shown guess read the same table, so this is
     * also the test that the SQL rendering of it agrees with the Kotlin one.
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
}
