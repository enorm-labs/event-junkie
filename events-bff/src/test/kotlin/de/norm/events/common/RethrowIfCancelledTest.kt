package de.norm.events.common

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RethrowIfCancelledTest {
    @Test
    fun `returns while the job is active`() =
        runTest {
            rethrowIfCancelled()
        }

    @Test
    fun `throws once the job is cancelled`() =
        runTest {
            var reachedAfter = false
            val job =
                launch {
                    cancel()
                    rethrowIfCancelled()
                    reachedAfter = true
                }
            job.join()
            reachedAfter shouldBe false
        }
}
