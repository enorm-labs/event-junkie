package de.norm.events.common

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The first line of the catch-all in an entry point Spring schedules. A cancelled job, at shutdown, throws here and stops quietly.
 * A stray `CancellationException`, such as a cancelled future's while the job runs on, falls through to be logged as a failure:
 * one escaping a `@Scheduled` suspend function cancels that schedule for the life of the process (see #3541).
 */
suspend fun rethrowIfCancelled() = currentCoroutineContext().ensureActive()
