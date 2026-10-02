package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.venue.VenueProgrammeStore
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [VenueProgrammeStore] and [VenueProgrammeSweep] against a real database (#327): the window, the
 * share and count thresholds, the write that happens only on a change, and the pass that waits for
 * imports. The sweep bean is off in tests (`app.scheduling.enabled: false`), so each test builds one.
 */
class VenueProgrammeSweepIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var venueProgrammeStore: VenueProgrammeStore

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    private var rockClub: Long = 0
    private var quizBar: Long = 0
    private var emptyHall: Long = 0
    private var counter = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            listOf("rock" to "rock", "punk" to "punk", "jazz" to "jazz-blues", "techno" to "electronic").forEach { (slug, family) ->
                databaseClient.sql("INSERT INTO events.genre_tag (name, slug, family) VALUES ('$slug', '$slug', '$family')").await()
            }
            rockClub = insertVenue("rock-club")
            quizBar = insertVenue("quiz-bar")
            emptyHall = insertVenue("empty-hall")

            repeat(6) { insertEvent(rockClub, "CONCERT", tags = listOf("rock")) }
            // Techno on two events is under the count, however large its share.
            repeat(3) { i -> insertEvent(rockClub, "PARTY", tags = if (i < 2) listOf("punk", "techno") else listOf("punk")) }
            insertEvent(rockClub, "COMEDY", tags = listOf("jazz"))
            // Outside the window, and cancelled: neither counts.
            repeat(5) { insertEvent(rockClub, "SHOW", date = "2024-05-01", tags = listOf("jazz")) }
            repeat(3) { insertEvent(rockClub, "READING", status = "CANCELLED", tags = listOf("jazz")) }

            // 4 of 30 is 13 %, under the share; OTHER never counts.
            repeat(23) { insertEvent(quizBar, "CONCERT") }
            repeat(4) { insertEvent(quizBar, "QUIZ") }
            repeat(3) { insertEvent(quizBar, "OTHER") }
        }

    @Test
    fun `derives families and event types over the window, most frequent first, above both thresholds`(): Unit =
        runBlocking {
            sweep().runOnce() shouldBe 2L

            programme(rockClub) shouldBe (listOf("rock", "punk") to listOf("CONCERT", "PARTY"))
            programme(quizBar) shouldBe (emptyList<String>() to listOf("CONCERT"))
            programme(emptyHall) shouldBe (emptyList<String>() to emptyList())
        }

    @Test
    fun `writes nothing when the programme has not changed, so updated_at stays put`(): Unit =
        runBlocking {
            sweep().runOnce()
            val before = updatedAt(rockClub)

            sweep().runOnce() shouldBe 0L

            updatedAt(rockClub) shouldBe before
        }

    @Test
    fun `refreshes one venue and leaves the others alone`(): Unit =
        runBlocking {
            venueProgrammeStore.refresh(rockClub, SINCE) shouldBe 1L

            programme(rockClub).second shouldBe listOf("CONCERT", "PARTY")
            programme(quizBar).second shouldBe emptyList()
        }

    @Test
    fun `does nothing while an import runs`(): Unit =
        runBlocking {
            databaseClient
                .sql(
                    "INSERT INTO events.event_source (venue_id, name, slug, url, source_type, status) " +
                        "VALUES ($rockClub, 'running', 'running', 'https://running.example/events', 'CASSIOPEIA', 'RUNNING')"
                ).await()

            sweep().runOnce().shouldBeNull()

            programme(rockClub) shouldBe (emptyList<String>() to emptyList())
        }

    private fun sweep() = VenueProgrammeSweep(venueProgrammeStore, eventSourceRepository, Clock.fixed(NOW, ZoneOffset.UTC))

    private suspend fun insertVenue(slug: String): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug) VALUES ('$slug', '$slug') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        venueId: Long,
        type: String,
        date: String = "2026-09-01",
        status: String = "SCHEDULED",
        tags: List<String> = emptyList()
    ) {
        val key = "e${counter++}"
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id, event_type, status) " +
                    "VALUES ($venueId, '$key', '$key', DATE '$date', '$key', '$type', '$status')"
            ).await()
        tags.forEach { tag ->
            databaseClient
                .sql(
                    "INSERT INTO events.event_genre_tag (event_id, genre_tag_id) " +
                        "SELECT e.id, g.id FROM events.event e, events.genre_tag g WHERE e.source_id = '$key' AND g.slug = '$tag'"
                ).await()
        }
    }

    private suspend fun programme(venueId: Long): Pair<List<String>, List<String>> =
        databaseClient
            .sql("SELECT programme_families, programme_event_types FROM events.venue WHERE id = $venueId")
            .map { row, _ ->
                row.get("programme_families", Array<String>::class.java)!!.toList() to
                    row.get("programme_event_types", Array<String>::class.java)!!.toList()
            }.awaitSingle()

    private suspend fun updatedAt(venueId: Long): Instant =
        databaseClient
            .sql("SELECT updated_at FROM events.venue WHERE id = $venueId")
            .map { row, _ -> row.get("updated_at", Instant::class.java)!! }
            .awaitSingle()

    private companion object {
        val NOW: Instant = Instant.parse("2026-10-02T03:45:00Z")
        val SINCE: LocalDate = LocalDate.parse("2025-10-02")
    }
}
