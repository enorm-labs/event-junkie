package de.norm.events.wikimedia

import tools.jackson.databind.JsonNode

/**
 * The lead of one Wikipedia article as its REST summary states it, before any rule decides whether
 * it may be stored (ADR-031, step C+). The text is CC BY-SA 4.0 on both wikis; [pageUrl] is what
 * the credit links to.
 */
data class WikipediaExtract(
    /** `de` or `en`: the wiki the article is on, and so the language of [text]. */
    val language: String,
    val text: String,
    val pageUrl: String
) {
    companion object {
        private const val STANDARD_PAGE = "standard"

        /** The lead of a standard page, or null for a disambiguation page or a summary without a text. */
        fun fromSummary(
            language: String,
            summary: JsonNode
        ): WikipediaExtract? {
            val text =
                summary
                    .path("extract")
                    .asString(null)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            val page =
                summary
                    .path("content_urls")
                    .path("desktop")
                    .path("page")
                    .asString(null)
                    ?.takeIf { it.isNotBlank() }
            return if (summary.path("type").asString("") == STANDARD_PAGE && text != null && page != null) {
                WikipediaExtract(language = language, text = text, pageUrl = page)
            } else {
                null
            }
        }
    }
}
