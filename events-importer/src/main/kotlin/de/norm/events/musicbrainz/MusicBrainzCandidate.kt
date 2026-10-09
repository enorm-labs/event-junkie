package de.norm.events.musicbrainz

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One artist as the MusicBrainz search returns it, reduced to what the match rule reads.
 *
 * `score`, `genres` and `tags` are deliberately not mapped. The score is a relevance figure and not
 * a confidence — `Pici` returns `Pici Mazzei` at 100 — and genres and tags are the same
 * CC BY-NC-SA list, which this project never imports (ADR-031). [type], [disambiguation], [area]
 * and [lifeSpan] are read only for the admin's review of an AMBIGUOUS name (#2946), never stored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzCandidate(
    /** The MBID, which is what an EXACT verdict stores. */
    val id: String,
    /** The primary name. The only field that can make a match. */
    val name: String,
    /** The name as MusicBrainz sorts it (`Beatles, The`). Equality here is AMBIGUOUS, never EXACT. */
    @param:JsonProperty("sort-name") val sortName: String? = null,
    /** ISO 3166-1 code, `DE` for the tie-break between several primary-name matches. */
    val country: String? = null,
    /** Other names the artist is known by. Equality here is AMBIGUOUS, never EXACT. */
    val aliases: List<MusicBrainzAlias> = emptyList(),
    /** `Person`, `Group`, `Orchestra`, `Choir`, `Character`, `Other`, or null when MusicBrainz has not typed it. */
    val type: String? = null,
    /** MusicBrainz's own note that tells namesakes apart (`German punk band`). */
    val disambiguation: String? = null,
    /** The area the artist is most associated with, a country or a city. */
    val area: MusicBrainzArea? = null,
    /** For a person a birth date, which [de.norm.events.enrichment.MusicBrainzCandidateController] never passes on. */
    @param:JsonProperty("life-span") val lifeSpan: MusicBrainzLifeSpan? = null
)

/** One alias of a [MusicBrainzCandidate]; only its name matters here. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzAlias(
    val name: String
)

/** The search response's envelope: the candidates, and nothing else read. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzSearchResponse(
    val artists: List<MusicBrainzCandidate> = emptyList()
)
