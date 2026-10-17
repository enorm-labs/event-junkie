package de.norm.events.common

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class RunCatchingCancellableTest {
    @Test
    fun `a value is a success`() {
        runCatchingCancellable { 42 } shouldBe Result.success(42)
    }

    @Test
    fun `an exception is a failure, as with runCatching`() {
        runCatchingCancellable { error("down") }.exceptionOrNull().shouldBeInstanceOf<IllegalStateException>()
    }

    @Test
    fun `a cancellation is rethrown, not caught`() {
        shouldThrow<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }
    }

    @Test
    fun `rethrowIfCancelled returns while the job is active`() =
        runTest {
            rethrowIfCancelled()
        }

    @Test
    fun `rethrowIfCancelled throws once the job is cancelled`() =
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
