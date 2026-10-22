package de.norm.events.dataquality

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException

/**
 * The report and the worklist — Pillar 1's whole API surface.
 *
 * Under the `/api/admin` prefix like every other admin endpoint, which is what keeps it cluster-internal:
 * no Ingress path routes to the importer, so this is reachable through a port-forward and not from
 * the internet (ADR-012, PLATFORM_SETUP §3.2). That matters more here than for most admin endpoints,
 * because a per-source quality report is a map of exactly where the data is weakest.
 *
 * **It writes no event.** Stewards fix an event through `PUT /api/admin/events/{id}`, from the admin
 * app's worklist page (`events-admin/`, #345). The one write here is a dismissal: a row judged correct
 * as it is, which ends when a flagged value changes (ADR-052). Dashboards read `data_quality_snapshot`
 * or Prometheus (strategy §6).
 */
@RestController
@RequestMapping("/api/admin/data-quality")
@Tag(name = "Admin: Data Quality", description = "Per-source data-quality measurement (Pillar 1 — Measure)")
class DataQualityController(
    private val service: DataQualityService,
    private val dismissals: WorklistDismissalService
) {
    @Operation(
        summary = "Per-source data-quality metrics with an overall roll-up",
        description =
            "Computed live on each request against the current table, so it always describes now. " +
                "For trends use the daily data_quality_snapshot rows or the Prometheus gauges — a " +
                "point-in-time report cannot show whether anything moved."
    )
    @GetMapping
    suspend fun report(): DataQualityReportResponse = service.report()

    /**
     * The offending events for one metric.
     *
     * An unknown `issue` is a **400 naming every valid value**, not a 404 and not an empty list. An
     * empty list is the answer to "this metric has no offenders", and returning it for a typo would
     * report perfect data quality for a metric that does not exist — which is the most misleading
     * thing this endpoint could do.
     *
     * `ServerWebInputException` rather than a bare `ResponseStatusException`, so these render as the
     * same RFC 9457 Problem Detail as every other bad request in this application — `GlobalException
     * Handler` has a handler for it, and none for the parent type. A caller parsing `$.detail` should
     * not have to special-case which endpoint refused them.
     */
    @Operation(
        summary = "The events failing one metric, so a steward can fix them",
        description =
            "Page through these and fix each via PUT /api/admin/events/{id}. Ordered newest-first " +
                "by event date, with id as a tie-break so a page boundary is stable."
    )
    @GetMapping("/worklist")
    suspend fun worklist(
        @Parameter(description = "Which metric's offenders to list", example = "concertsWithoutArtist")
        @RequestParam issue: String,
        @Parameter(description = "Restrict to one source slug, or 'manual' for hand-created events")
        @RequestParam(required = false) source: String?,
        @Parameter(description = "Page size, 1..$MAX_LIMIT")
        @RequestParam(defaultValue = "$DEFAULT_LIMIT") limit: Int,
        @Parameter(description = "Rows to skip")
        @RequestParam(defaultValue = "0") offset: Int
    ): WorklistResponse {
        val resolved = resolve(issue)
        validatePaging(limit, offset)
        return service.worklist(resolved, source, limit, offset)
    }

    @Operation(
        summary = "Judge one worklist row correct as it is",
        description =
            "The row leaves GET /worklist until a value the check flagged changes (ADR-052). Send the entry's " +
                "fingerprint: a changed value, or an event no longer on that worklist, is a 409. Dismissing " +
                "again replaces the fingerprint. unreviewedLicence cannot be dismissed (400)."
    )
    @PostMapping("/worklist/dismissals")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun dismiss(
        @Valid @RequestBody request: WorklistDismissalRequest
    ): WorklistDismissalResponse {
        val resolved = resolveDismissable(requireNotNull(request.issue))
        return dismissals.dismiss(resolved, requireNotNull(request.eventId), requireNotNull(request.fingerprint))
    }

    @Operation(summary = "Undo a dismissal, so its row is back on the worklist", description = "An unknown id is a 404.")
    @DeleteMapping("/worklist/dismissals/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun undismiss(
        @Parameter(description = "The dismissal's id", example = "7")
        @PathVariable id: Long
    ) = dismissals.undismiss(id)

    @Operation(
        summary = "The dismissals of one metric, the newest first",
        description = "An inactive dismissal hides nothing: its row changed and is back on the worklist."
    )
    @GetMapping("/worklist/dismissals")
    suspend fun dismissals(
        @Parameter(description = "Which metric's dismissals to list", example = "titleDerivedSingletons")
        @RequestParam issue: String
    ): List<WorklistDismissalResponse> = dismissals.dismissals(resolveDismissable(issue))

    private fun resolve(issue: String): QualityIssue =
        QualityIssue.byKey(issue)
            ?: throw ServerWebInputException(
                "Unknown issue '$issue'. Valid values: ${QualityIssue.KEYS.joinToString(", ")}"
            )

    private fun resolveDismissable(issue: String): QualityIssue =
        resolve(issue).also {
            if (it.fingerprint == null) {
                throw ServerWebInputException("Issue '$issue' cannot be dismissed: only the work it counts clears a row")
            }
        }

    /**
     * One `throw`, and the first problem wins.
     *
     * Reporting only the first is deliberate: a caller fixing `limit=100000` and then discovering
     * `offset=-1` learns both, one round-trip apart, and neither message is ambiguous. Accumulating
     * them would mean inventing a multi-error shape for two integers.
     */
    private fun validatePaging(
        limit: Int,
        offset: Int
    ) {
        val problem =
            when {
                limit !in 1..MAX_LIMIT -> "limit must be between 1 and $MAX_LIMIT"
                offset < 0 -> "offset must not be negative"
                else -> return
            }
        throw ServerWebInputException(problem)
    }

    private companion object {
        const val DEFAULT_LIMIT = 50

        /**
         * A bound rather than trust. The worklist is meant to be worked through, so a caller asking
         * for every offending event at once is asking for a payload nobody reads and a query that
         * holds a connection while it builds it.
         */
        const val MAX_LIMIT = 500
    }
}
