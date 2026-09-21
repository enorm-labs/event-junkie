package de.norm.events.musicbrainz

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * One artist entity as `GET artist/{mbid}?inc=url-rels` returns it, reduced to what step C reads (ADR-031).
 *
 * `gender` is deliberately not mapped, and `life-span.begin` and `begin-area` are read only for an
 * ensemble by [de.norm.events.scraper.ArtistEnrichment]: for a person they are a birth date and a
 * birthplace, which the privacy notice promises not to hold. `genres`, `tags` and `rating` are the
 * CC BY-NC-SA half and are never requested.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzArtist(
    val id: String,
    val name: String,
    /** `Person`, `Group`, `Orchestra`, `Choir`, `Character`, `Other`, or null when MusicBrainz has not typed it. */
    val type: String? = null,
    /** ISO 3166-1 code of the country the act is from. */
    val country: String? = null,
    @param:JsonProperty("life-span") val lifeSpan: MusicBrainzLifeSpan? = null,
    @param:JsonProperty("begin-area") val beginArea: MusicBrainzArea? = null,
    val relations: List<MusicBrainzUrlRelation> = emptyList()
)

/** MusicBrainz's partial dates: `1986`, `1986-09` or `1986-09-06`. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzLifeSpan(
    val begin: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzArea(
    val name: String? = null
)

/**
 * One URL relationship. [type] is MusicBrainz's relationship name (`official homepage`,
 * `social network`, `bandcamp`, …); [ended] marks a link the act has left behind, which is skipped.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzUrlRelation(
    val type: String,
    val ended: Boolean = false,
    val url: MusicBrainzUrl
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MusicBrainzUrl(
    val resource: String
)
