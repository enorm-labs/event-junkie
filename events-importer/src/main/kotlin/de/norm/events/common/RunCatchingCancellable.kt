package de.norm.events.common

import io.github.oshai.kotlinlogging.KLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * [runCatching] for a block that suspends: the same [Result], but a [CancellationException] is
 * rethrown, so a cancelled coroutine stops instead of logging a failure and running on (#3541).
 */
@Suppress("TooGenericExceptionCaught")
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Result.failure(e)
    }

/**
 * The first line of the catch-all in an entry point Spring schedules or starts. A cancelled job, at
 * shutdown, throws here and stops quietly. A stray [CancellationException], such as a cancelled
 * future's while the job runs on, falls through to be logged as a failure (#3541).
 */
suspend fun rethrowIfCancelled() = currentCoroutineContext().ensureActive()

/**
 * The body of a `@Scheduled` suspend function, with the catch-all every one of them carries. Spring
 * runs the next tick either way (`ScheduledFailureTest`), but logs an escaped failure as one `WARN`
 * on its own logger, naming no pass. Here it is one `ERROR` line, `"<what> failed"`, on [logger].
 */
@Suppress("TooGenericExceptionCaught", "SuspendFunSwallowedCancellation")
suspend fun runScheduled(
    what: String,
    logger: KLogger,
    block: suspend () -> Unit
) {
    try {
        block()
    } catch (e: Exception) {
        rethrowIfCancelled()
        logger.error(e) { "$what failed" }
    }
}
