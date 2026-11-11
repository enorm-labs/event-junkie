package de.norm.events.image

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test

/** The two image ticks swallow a failure, so Spring keeps scheduling them, and stop on a cancellation. */
class ScheduledImageCacheServiceTest {
    private val cache: ImageCacheService = mockk()
    private val derivatives: ImageDerivativeService = mockk(relaxed = true)
    private val removal: ImageRemovalService = mockk()
    private val service = ScheduledImageCacheService(cache, derivatives, removal)

    @Test
    fun `a failed fetch pass is logged, skips the derivatives and returns`() =
        runTest {
            coEvery { cache.refreshBatch() } throws IllegalStateException("bucket down")

            service.tick()

            coVerify(exactly = 0) { derivatives.generateBatch() }
        }

    @Test
    fun `a stray cancellation while the tick is not cancelled is logged as a failure`() =
        runTest {
            coEvery { removal.sweep() } throws CancellationException("a cancelled future")

            service.sweepTick()
        }

    @Test
    fun `a cancelled tick stops instead of returning`() =
        runTest {
            coEvery { cache.refreshBatch() } coAnswers { awaitCancellation() }
            var returned = false

            val job = launch { service.tick().also { returned = true } }
            yield()
            job.cancel()
            job.join()

            coVerify { cache.refreshBatch() }
            returned shouldBe false
        }

    @Test
    fun `a cancelled sweep stops instead of returning`() =
        runTest {
            coEvery { removal.sweep() } coAnswers { awaitCancellation() }
            var returned = false

            val job = launch { service.sweepTick().also { returned = true } }
            yield()
            job.cancel()
            job.join()

            coVerify { removal.sweep() }
            returned shouldBe false
        }
}
