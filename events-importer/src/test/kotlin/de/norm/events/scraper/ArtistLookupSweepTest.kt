package de.norm.events.scraper

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class ArtistLookupSweepTest {
    private val musicBrainzLookupService: MusicBrainzLookupService = mockk(relaxed = true)
    private val musicBrainzEnrichmentService: MusicBrainzEnrichmentService = mockk(relaxed = true)
    private val discogsLookupService: DiscogsLookupService = mockk(relaxed = true)

    private fun sweep(enabled: Boolean = true) = ArtistLookupSweep(musicBrainzLookupService, musicBrainzEnrichmentService, discogsLookupService, enabled)

    @Test
    fun `a tick runs the lookup, then the enrichment, then Discogs, with the queued artists`() =
        runTest {
            val sweep = sweep()
            sweep.queue(setOf(1L, 2L))
            sweep.queue(setOf(2L, 3L))

            sweep.tick()

            coVerifyOrder {
                musicBrainzLookupService.sweep(setOf(1L, 2L, 3L))
                musicBrainzEnrichmentService.sweep(setOf(1L, 2L, 3L))
                discogsLookupService.sweep(setOf(1L, 2L, 3L))
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
