package de.norm.events.common

import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.data.domain.Pageable

/**
 * Generic paged response wrapper for the admin API's list endpoints.
 *
 * **A bare array cannot say it was cut short.** Every list here applies a default page size, so
 * twenty rows and twenty-of-eighty-six looked identical to a client — which is how
 * `scripts/apply-licence-review.py` wrote 20 of 86 sources and reported success (#810). The total is
 * what makes a partial read detectable, and a client that ignores it now has to ignore something.
 *
 * A deliberate copy of `events-bff`'s type rather than a shared one, for the same reason
 * [de.norm.events.StableSortPageableArgumentResolver] is duplicated: `events-core` is free of web
 * dependencies, and this carries springdoc annotations. The two must stay the same shape, because
 * one API answering in a different envelope from the other is the asymmetry #810 was about.
 */
@Schema(description = "A page of results with pagination metadata")
data class PageResponse<T>(
    @Schema(description = "The items on this page")
    val content: List<T>,
    @Schema(description = "Zero-based index of this page", example = "0")
    val page: Int,
    @Schema(description = "Requested page size", example = "20")
    val size: Int,
    @Schema(description = "Total number of matching items across all pages", example = "86")
    val totalElements: Long,
    @Schema(description = "Total number of pages", example = "5")
    val totalPages: Int
) {
    companion object {
        /** Builds a [PageResponse] from the page [content], the requesting [pageable], and the overall [totalElements] count. */
        fun <T> of(
            content: List<T>,
            pageable: Pageable,
            totalElements: Long
        ): PageResponse<T> =
            PageResponse(
                content = content,
                page = pageable.pageNumber,
                size = pageable.pageSize,
                totalElements = totalElements,
                totalPages = if (pageable.pageSize == 0) 0 else ((totalElements + pageable.pageSize - 1) / pageable.pageSize).toInt()
            )
    }
}
