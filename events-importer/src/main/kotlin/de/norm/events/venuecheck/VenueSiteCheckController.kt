package de.norm.events.venuecheck

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** One venue on the "needs review" list. */
@Schema(description = "A venue without an importer whose site failed the monthly check several times in a row")
data class NeedsReviewResponse(
    @Schema(description = "Database ID of the venue.", example = "42")
    val venueId: Long,
    @Schema(description = "Slug of the venue.", example = "funkloch")
    val slug: String,
    @Schema(description = "Name of the venue.", example = "Funkloch")
    val name: String,
    @Schema(description = "The venue's own website.", example = "https://funkloch.berlin/")
    val websiteUrl: String?,
    @Schema(description = "Where a visitor finds the venue's programme.", example = "https://funkloch.berlin/programm")
    val programmeUrl: String?,
    @Schema(description = "When a person last confirmed the venue's facts; null when nobody did.")
    val reviewedAt: Instant?,
    @Schema(description = "When the last check ran.")
    val checkedAt: Instant,
    @Schema(description = "The URL whose failure the last check reports.", example = "https://funkloch.berlin/")
    val url: String?,
    @Schema(description = "What the last check found: HTTP, DNS, TLS, TIMEOUT, CONNECTION, OTHER, or SKIPPED.", example = "DNS")
    val outcome: SiteOutcome,
    @Schema(description = "The HTTP status of an HTTP outcome.", example = "404")
    val httpStatus: Int?,
    @Schema(description = "Checks in a row that failed.", example = "3")
    val consecutiveFailures: Int,
    @Schema(description = "When the current run of failures began.")
    val failingSince: Instant?
) {
    companion object {
        fun of(row: NeedsReviewRow) =
            NeedsReviewResponse(
                venueId = row.venueId,
                slug = row.slug,
                name = row.name,
                websiteUrl = row.websiteUrl,
                programmeUrl = row.programmeUrl,
                reviewedAt = row.reviewedAt,
                checkedAt = row.checkedAt,
                url = row.url,
                outcome = row.outcome,
                httpStatus = row.httpStatus,
                consecutiveFailures = row.consecutiveFailures,
                failingSince = row.failingSince
            )
    }
}

/**
 * The site check's admin routes (#2812). Under `/api/admin` like every admin endpoint, so no Ingress path reaches
 * them. Neither route changes a venue: a person confirms a closure by setting `closed_on`, and a review by setting
 * `reviewed_at`, both through the venue routes.
 */
@RestController
@RequestMapping("/api/admin/venues")
@Tag(name = "Admin: Venue site check", description = "Venues without an importer whose site stopped answering")
class VenueSiteCheckController(
    private val service: VenueSiteCheckService,
    @Qualifier("ioDispatcher") ioDispatcher: CoroutineDispatcher
) : DisposableBean {
    /** Outlives the request, so a pass is not cancelled when the caller's port-forward times out. */
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)

    /**
     * Venues whose site failed [VenueSiteCheckService.REVIEW_THRESHOLD] monthly checks in a row and that nobody
     * reviewed since the failures began. Setting `reviewed_at` takes a venue off the list until a new run of
     * failures begins.
     */
    @GetMapping("/needs-review")
    @Operation(summary = "List venues without an importer whose site failed three checks in a row")
    suspend fun needsReview(): List<NeedsReviewResponse> = service.needsReview().map(NeedsReviewResponse::of)

    /** Starts one pass now instead of on the 1st of the month. Returns at once; the pass logs its result. */
    @PostMapping("/site-check")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Run the venue site check once, in the background")
    fun runSiteCheck() {
        scope.launch { service.runOnce() }
    }

    override fun destroy() = scope.cancel()
}
