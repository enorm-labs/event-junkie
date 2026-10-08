package de.norm.events.importing

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.enrichment.DiscogsLookupService
import de.norm.events.enrichment.LookupPass
import de.norm.events.enrichment.MusicBrainzEnrichmentService
import de.norm.events.enrichment.MusicBrainzLookupService
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class ArtistLookupSweepTest {
    private val musicBrainzLookupService: MusicBrainzLookupService = mockk()
    private val musicBrainzEnrichmentService: MusicBrainzEnrichmentService = mockk()
    private val discogsLookupService: DiscogsLookupService = mockk()
    private val artistOccupationService: ArtistOccupationService = mockk()
    private val performerTyping: PerformerTyping = mockk()
    private val registry = SimpleMeterRegistry()

    private lateinit var appender: ListAppender<ILoggingEvent>
    private val logger = LoggerFactory.getLogger(ArtistLookupSweep::class.java) as Logger

    @BeforeEach
    fun setUp() {
        coEvery { musicBrainzLookupService.sweep(any()) } returns LookupPass(owed = 0, stored = 0)
        coEvery { musicBrainzEnrichmentService.sweep(any()) } returns LookupPass(owed = 0, stored = 0)
        coEvery { discogsLookupService.sweep(any()) } returns LookupPass(owed = 0, stored = 0)
        coEvery { artistOccupationService.sweep() } returns LookupPass(owed = 0, stored = 0)
        coEvery { performerTyping.retypeStored() } returns 0
        appender = ListAppender<ILoggingEvent>().apply { start() }
        logger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        logger.detachAppender(appender)
        appender.stop()
    }

    private fun sweep(enabled: Boolean = true) =
        ArtistLookupSweep(
            musicBrainzLookupService,
            musicBrainzEnrichmentService,
            discogsLookupService,
            artistOccupationService,
            performerTyping,
            ImporterMetrics(registry),
            enabled
        )

    private fun tickLines() = appender.list.filter { it.level == Level.INFO && it.formattedMessage.startsWith("Artist lookup tick:") }

    private fun lastSuccess() = registry.find("importer.artists.lookup_tick.last_success").gauge()?.value()

    @Test
    fun `a tick that owed nothing still says it ran, and stamps the gauge`() =
        runTest {
            lastSuccess() shouldBe null
            val sweep = sweep()
            lastSuccess() shouldBe 0.0

            sweep.tick()

            tickLines().map { it.formattedMessage } shouldBe
                listOf("Artist lookup tick: 0 touched · MusicBrainz 0 of 0 · enrichment 0 of 0 · Discogs 0 of 0 · occupations 0 of 0 · retyped 0")
            lastSuccess().shouldNotBeNull() shouldBeGreaterThan 0.0
        }

    @Test
    fun `the line names a switched-off lookup and a failed pass, and the tick still counts as run`() =
        runTest {
            coEvery { musicBrainzLookupService.sweep(any()) } returns LookupPass(owed = 3, stored = 2)
            coEvery { musicBrainzEnrichmentService.sweep(any()) } throws IllegalStateException("boom")
            coEvery { discogsLookupService.sweep(any()) } returns LookupPass.OFF
            coEvery { artistOccupationService.sweep() } returns LookupPass(owed = 5, stored = 5)
            coEvery { performerTyping.retypeStored() } returns 2
            val sweep = sweep()
            sweep.queue(setOf(1L, 2L, 3L))

            sweep.tick()

            tickLines().single().formattedMessage shouldBe
                "Artist lookup tick: 3 touched · MusicBrainz 2 of 3 · enrichment failed · Discogs off · occupations 5 of 5 · retyped 2"
            lastSuccess().shouldNotBeNull() shouldBeGreaterThan 0.0
        }

    @Test
    fun `a switched-off tick writes no line and leaves the gauge at zero`() =
        runTest {
            val sweep = sweep(enabled = false)

            sweep.tick()

            tickLines().shouldBeEmpty()
            lastSuccess() shouldBe 0.0
        }

    @Test
    fun `a tick runs the lookup, the enrichment and Discogs with the queued artists, then the occupations and the retype`() =
        runTest {
            val sweep = sweep()
            sweep.queue(setOf(1L, 2L))
            sweep.queue(setOf(2L, 3L))

            sweep.tick()

            coVerifyOrder {
                musicBrainzLookupService.sweep(setOf(1L, 2L, 3L))
                musicBrainzEnrichmentService.sweep(setOf(1L, 2L, 3L))
                discogsLookupService.sweep(setOf(1L, 2L, 3L))
                artistOccupationService.sweep()
                performerTyping.retypeStored()
            }
        }

    @Test
    fun `a queued artist is handed over once`() =
        runTest {
            val sweep = sweep()
            sweep.queue(setOf(7L))

            sweep.tick()
            sweep.tick()

            coVerify(exactly = 1) { musicBrainzLookupService.sweep(setOf(7L)) }
            coVerify(exactly = 1) { musicBrainzLookupService.sweep(emptySet()) }
        }

    @Test
    fun `a pass that throws does not stop the passes after it`() =
        runTest {
            coEvery { musicBrainzLookupService.sweep(any()) } throws IllegalStateException("boom")
            coEvery { musicBrainzEnrichmentService.sweep(any()) } throws IllegalStateException("boom")

            sweep().tick()

            coVerify { discogsLookupService.sweep(any()) }
        }

    @Test
    fun `a switched-off tick queues nothing and asks nobody`() =
        runTest {
            val sweep = sweep(enabled = false)
            sweep.queue(setOf(1L))

            sweep.tick()

            coVerify(exactly = 0) { musicBrainzLookupService.sweep(any()) }
            coVerify(exactly = 0) { discogsLookupService.sweep(any()) }
        }
}
