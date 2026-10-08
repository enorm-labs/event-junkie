package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.UncreditedPromoterStore
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [OrphanPromoterSweep] against a real database (#2653): which rows the delete takes, which it
 * keeps, what it logs, and that it does nothing while an import runs. The bean itself is off in
 * tests (`app.scheduling.enabled: false`), so each test builds one.
 */
class OrphanPromoterSweepIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var promoterRepository: PromoterRepository

    @Autowired
    private lateinit var uncreditedPromoterStore: UncreditedPromoterStore

    @Autowired
    private lateinit var eventSourceRepository: EventSourceRepository

    private var venueId: Long = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId = insertVenue()
            insertEvent("e1")
            insertPromoter("Credited", "credited", ageDays = 30)
            link("e1", "credited")
            insertPromoter("Trinity MusicMus", "trinity-musicmus", ageDays = 30)
            insertPromoter("Agave", "agave", ageDays = 30, extra = "website_url = 'https://agave.example', reviewed_at = NOW()")
            insertPromoter("Minted Today", "minted-today", ageDays = 0)
            insertPromoter("Described", "described", ageDays = 30, extra = "description = 'Eine Beschreibung.', description_language = 'de'")
        }

    @Test
    fun `deletes old uncredited rows, a reviewed one too, and keeps credited, new and described rows`(): Unit =
        runBlocking {
            sweepAt(Instant.now()).runOnce() shouldBe listOf("agave", "trinity-musicmus")

            slugs() shouldContainExactlyInAnyOrder listOf("credited", "minted-today", "described")
        }

    @Test
    fun `logs the slugs it deletes`(): Unit =
        runBlocking {
            val logger = LoggerFactory.getLogger(OrphanPromoterSweep::class.java) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            logger.addAppender(appender)
            try {
                sweepAt(Instant.now()).runOnce()
            } finally {
                logger.detachAppender(appender)
            }

            appender.list.map { it.level to it.formattedMessage } shouldContain
                (Level.INFO to "Orphan promoter sweep deleted 2 promoter(s) no event credits: agave, trinity-musicmus")
        }

    @Test
    fun `does nothing while an import runs`(): Unit =
        runBlocking {
            insertSource("running", ImportStatus.RUNNING)

            sweepAt(Instant.now()).runOnce().shouldBeNull()

            slugs() shouldContainExactlyInAnyOrder listOf("credited", "trinity-musicmus", "agave", "minted-today", "described")
        }

    private fun sweepAt(now: Instant) = OrphanPromoterSweep(uncreditedPromoterStore, eventSourceRepository, Clock.fixed(now, ZoneOffset.UTC))

    private suspend fun slugs(): List<String> = promoterRepository.findAll().map { it.slug }.toList()

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug) VALUES ('Test Venue', 'test-venue') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(sourceId: String) =
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id) " +
                    "VALUES ($venueId, '$sourceId', '$sourceId', DATE '2026-01-01', '$sourceId')"
            ).await()

    private suspend fun insertPromoter(
        name: String,
        slug: String,
        ageDays: Int,
        extra: String? = null
    ) {
        databaseClient
            .sql("INSERT INTO events.promoter (name, slug, created_at) VALUES ('$name', '$slug', NOW() - INTERVAL '$ageDays days')")
            .await()
        extra?.let { databaseClient.sql("UPDATE events.promoter SET $it WHERE slug = '$slug'").await() }
    }

    private suspend fun link(
        sourceId: String,
        promoterSlug: String
    ) = databaseClient
        .sql(
            "INSERT INTO events.event_promoter (event_id, promoter_id) " +
                "SELECT e.id, p.id FROM events.event e, events.promoter p WHERE e.source_id = '$sourceId' AND p.slug = '$promoterSlug'"
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
