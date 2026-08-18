package de.norm.events.dataquality

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
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
 * **Read-only, and no bespoke frontend by design.** Stewards act through the existing
 * `PUT /api/admin/events/{id}`; dashboards come from an external BI tool reading
 * `data_quality_snapshot` or Prometheus (strategy §6). This endpoint's job is to expose the numbers
 * in a shape those consume.
 */
@RestController
@RequestMapping("/api/admin/data-quality")
@Tag(name = "Admin: Data Quality", description = "Per-source data-quality measurement (Pillar 1 — Measure)")
class DataQualityController(
    private val service: DataQualityService
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
        val resolved =
            QualityIssue.byKey(issue)
                ?: throw ServerWebInputException(
                    "Unknown issue '$issue'. Valid values: ${QualityIssue.KEYS.joinToString(", ")}"
                )
        validatePaging(limit, offset)
        return service.worklist(resolved, source, limit, offset)
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
