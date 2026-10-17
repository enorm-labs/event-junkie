package de.norm.events.common

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
 * future's while the job runs on, falls through to be logged as a failure: one escaping a
 * `@Scheduled` suspend function cancels that schedule for the life of the process (#3541).
 */
suspend fun rethrowIfCancelled() = currentCoroutineContext().ensureActive()
