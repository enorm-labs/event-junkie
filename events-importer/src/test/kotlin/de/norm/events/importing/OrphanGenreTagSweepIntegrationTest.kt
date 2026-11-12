package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.genretag.UnlinkedGenreTagStore
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [OrphanGenreTagSweep] against a real database (#4923): which tags the delete takes, which it keeps, what it logs,
 * and that it does nothing while an import runs. The bean itself is off in tests, so each test builds one.
 */
class OrphanGenreTagSweepIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var unlinkedGenreTagStore: UnlinkedGenreTagStore

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    private var venueId: Long = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId = insertVenue()
            insertEvent("past", "DATE '2020-01-01'")
            insertEvent("upcoming", "CURRENT_DATE + 7")
            insertTag("Techno", "techno", ageDays = 30)
            link("upcoming", "techno", "SOURCE")
            insertTag("Krautrock", "krautrock", ageDays = 30)
            link("past", "krautrock", "SOURCE")
            insertTag("Jazz", "jazz", ageDays = 30)
            link("upcoming", "jazz", "DESCRIPTION")
            insertTag("Psych", "psych", ageDays = 30)
            insertTag("Leipzig", "leipzig", ageDays = 30)
            insertTag("Minted Today", "minted-today", ageDays = 0)
        }

    @Test
    fun `deletes old unlinked tags, and keeps a source link, a past event's link, a derived link and a new tag`(): Unit =
        runBlocking {
            sweepAt(Instant.now()).runOnce() shouldBe listOf("leipzig", "psych")

            slugs() shouldContainExactlyInAnyOrder listOf("techno", "krautrock", "jazz", "minted-today")
        }

    @Test
    fun `logs the slugs it deletes`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(OrphanGenreTagSweep::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                sweepAt(Instant.now()).runOnce()
            } finally {
                logger.detachAppender(appender)
            }

            appender.list.map { it.level to it.formattedMessage } shouldContain
                (Level.INFO to "Orphan genre tag sweep deleted 2 genre tag(s) no event links: leipzig, psych")
        }

    @Test
    fun `does nothing while an import runs`(): Unit =
        runBlocking {
            insertSource("running", ImportStatus.RUNNING)

            sweepAt(Instant.now()).runOnce().shouldBeNull()

            slugs() shouldContainExactlyInAnyOrder listOf("techno", "krautrock", "jazz", "psych", "leipzig", "minted-today")
        }

    private fun sweepAt(now: Instant) = OrphanGenreTagSweep(unlinkedGenreTagStore, eventSourceRepository, Clock.fixed(now, ZoneOffset.UTC))

    private suspend fun slugs(): List<String> =
        databaseClient
            .sql("SELECT slug FROM events.genre_tag")
            .map { row, _ -> row.get("slug", String::class.java)!! }
            .flow()
            .toList()

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug) VALUES ('Test Venue', 'test-venue') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        sourceId: String,
        date: String
    ) = databaseClient
        .sql(
            "INSERT INTO events.event (venue_id, title, slug, event_date, source_id) " +
                "VALUES ($venueId, '$sourceId', '$sourceId', $date, '$sourceId')"
        ).await()

    private suspend fun insertTag(
        name: String,
        slug: String,
        ageDays: Int
    ) = databaseClient
        .sql("INSERT INTO events.genre_tag (name, slug, created_at) VALUES ('$name', '$slug', NOW() - INTERVAL '$ageDays days')")
        .await()

    private suspend fun link(
        sourceId: String,
        tagSlug: String,
        origin: String
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_genre_tag (event_id, genre_tag_id, origin) " +
                "SELECT e.id, t.id, '$origin' FROM events.event e, events.genre_tag t WHERE e.source_id = '$sourceId' AND t.slug = '$tagSlug'"
        ).await()

    private suspend fun insertSource(
        slug: String,
        status: ImportStatus
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_source (venue_id, name, slug, url, source_type, status) " +
                "VALUES ($venueId, '$slug', '$slug', 'https://$slug.example/events', 'CASSIOPEIA', '${status.name}')"
        ).await()
}
