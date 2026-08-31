package de.norm.events.scraper

import kotlinx.coroutines.slf4j.MDCContext
import org.slf4j.MDC
import java.util.UUID

/**
 * The context every log line emitted inside an import run carries (#380).
 *
 * **Set once, at the one funnel every import path goes through**, rather than passed at call sites.
 * [EventImportService.importFromSource] is that funnel — the scheduler's `importConcurrently`,
 * `importBySlug` and both of `ImportJobLauncher`'s fire-and-forget triggers all reach it — so a
 * single [forImportRun] there puts these fields on the output of all forty-odd scrapers without one
 * of them knowing this exists.
 *
 * **Why MDC rather than an argument.** Spring Boot's ECS structured logging copies every MDC entry
 * into the JSON object it writes, so a key put here becomes a field the log store can filter on.
 * Threading a slug through forty scrapers' signatures would produce the same text and none of the
 * queryability.
 *
 * **In this package rather than the root one**, because Spring Modulith reads the root package as
 * a slice of its own and it already depends on `scraper` through `GlobalExceptionHandler`. A
 * cross-cutting helper up there would close that into a cycle and fail `ModularityTests`.
 *
 * **[MDCContext] and not a bare [MDC.put].** MDC is thread-local and a coroutine changes threads at
 * every suspension point, so a plain put is gone by the time the next line is written — silently, as
 * an absent field rather than an error. [MDCContext] carries the map across dispatches.
 */
object LogContext {
    /** The event source being imported, e.g. `berghain`. The field to filter one venue's run by. */
    const val SOURCE_SLUG = "sourceSlug"

    /**
     * One id per import of one source, so a run can be read end to end.
     *
     * Deliberately not a trace id: there is no Micrometer Tracing on the classpath, and inventing a
     * field named `traceId` that no tracing system issued would be worse than not having one.
     */
    const val IMPORT_RUN_ID = "importRunId"

    /**
     * The context for one source's import run, merged over whatever the caller already carries so a
     * nested run cannot silently drop an outer field.
     */
    fun forImportRun(sourceSlug: String): MDCContext =
        MDCContext(
            (MDC.getCopyOfContextMap() ?: emptyMap()) +
                mapOf(
                    SOURCE_SLUG to sourceSlug,
                    IMPORT_RUN_ID to UUID.randomUUID().toString()
                )
        )
}
