package de.norm.events.discogs

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * One result of the Discogs database search, reduced to what the match rule reads.
 *
 * The thumbnail, the cover image and everything else the search returns are not mapped. The API
 * terms forbid storing Discogs content longer than a service needs it (#2026), and the rule needs
 * the id, the title and the page path only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscogsCandidate(
    /** The Discogs artist id, which an EXACT verdict stores. */
    val id: Long,
    /** `artist` for every result of a `type=artist` search; checked anyway. */
    val type: String = "",
    /** The name, with a homonym suffix such as `Hanzel (2)` when Discogs has several. */
    val title: String = "",
    /** The page path on discogs.com, as `/artist/130715-Okkyung-Lee`. */
    val uri: String? = null
)

/** The search response's envelope: the results, and nothing else read. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscogsSearchResponse(
    val results: List<DiscogsCandidate> = emptyList()
)

/**
 * One release in an artist's release list, reduced to its year (#2054). The match rule reads the
 * newest year and stores nothing; titles, labels and formats are not mapped. 0 is undated.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscogsRelease(
    val year: Int = 0
)

/** The release list's envelope: one page of releases, and nothing else read. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DiscogsReleasesResponse(
    val releases: List<DiscogsRelease> = emptyList()
)
