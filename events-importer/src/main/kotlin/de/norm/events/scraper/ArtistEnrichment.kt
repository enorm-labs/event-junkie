package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistType
import de.norm.events.musicbrainz.MusicBrainzArtist
import de.norm.events.musicbrainz.MusicBrainzUrlRelation
import de.norm.events.wikimedia.CommonsCredit
import de.norm.events.wikimedia.CommonsImage
import de.norm.events.wikimedia.CommonsLicences
import java.net.URI

/**
 * What an EXACT artist row is filled with from its MusicBrainz entity and its Commons picture
 * (ADR-031, step C), as the columns to write. Pure: the sweep reads, this decides, the store writes.
 *
 * **A column that holds a value is never touched**, #1319's rule for promoter websites: a person's
 * edit and a venue's own link both outrank a database. The first relationship of each kind wins in
 * MusicBrainz's order, an ended one is skipped, and Resident Advisor has no relationship type of its
 * own, so it is read by host from `other databases` as Facebook and Instagram are from
 * `social network`. `founded` and `foundedIn` are written for an ensemble only; for a person the
 * same MusicBrainz fields are a birth date and a birthplace, and V040's CHECK refuses them.
 */
object ArtistEnrichment {
    /** The columns to write, the fields they fill for the counter, and why a picture was not written. */
    data class Filled(
        val columns: Map<String, String>,
        val fields: List<String>,
        val imageRefusal: String?
    ) {
        val isEmpty: Boolean get() = columns.isEmpty()
    }

    /** The Wikidata item behind the entity, `Q3374548`, when it links one; the key the picture is read under. */
    fun wikidataIdOf(entity: MusicBrainzArtist): String? =
        entity.relations
            .firstOrNull { it.isLive && it.type == "wikidata" }
            ?.url
            ?.resource
            ?.substringAfterLast('/')
            ?.takeIf { WIKIDATA_ID.matches(it) }

    fun fill(
        artist: ArtistEntity,
        entity: MusicBrainzArtist,
        image: CommonsImage?,
        maxBytes: Long
    ): Filled {
        val columns = linkedMapOf<String, String>()
        val fields = mutableListOf<String>()

        fun fill(
            field: String,
            column: String,
            current: String?,
            value: String?
        ) {
            if (current == null && value != null) {
                columns[column] = value
                fields += field
            }
        }

        val links = linksOf(entity)
        fill("website", "website_url", artist.websiteUrl, links[Link.WEBSITE])
        fill("facebook", "facebook_url", artist.facebookUrl, links[Link.FACEBOOK])
        fill("instagram", "instagram_url", artist.instagramUrl, links[Link.INSTAGRAM])
        fill("youtube", "youtube_url", artist.youtubeUrl, links[Link.YOUTUBE])
        fill("bandcamp", "bandcamp_url", artist.bandcampUrl, links[Link.BANDCAMP])
        fill("soundcloud", "soundcloud_url", artist.soundcloudUrl, links[Link.SOUNDCLOUD])
        fill("discogs", "discogs_url", artist.discogsUrl, links[Link.DISCOGS])
        fill("wikidata", "wikidata_url", artist.wikidataUrl, links[Link.WIKIDATA])
        fill("resident_advisor", "resident_advisor_url", artist.residentAdvisorUrl, links[Link.RESIDENT_ADVISOR])
        fill("spotify", "spotify_url", artist.spotifyUrl, links[Link.SPOTIFY])

        val type = ArtistType.fromMusicBrainz(entity.type)
        fill("type", "artist_type", artist.artistType, type?.name)
        fill("country", "country", artist.country, entity.country?.takeIf { it.isNotBlank() })
        val storedType = artist.artistType?.let { ArtistType.valueOf(it) } ?: type
        if (storedType?.isEnsemble == true) {
            fill("founded", "founded", artist.founded, entity.lifeSpan?.begin?.takeIf { it.isNotBlank() })
            fill("founded_in", "founded_in", artist.foundedIn, entity.beginArea?.name?.takeIf { it.isNotBlank() })
        }

        var refusal: String? = null
        if (artist.imageUrl == null && image != null) {
            refusal = refuse(image, maxBytes)
            if (refusal == null) {
                columns["image_url"] = image.thumbUrl
                columns["image_attribution"] = "${CommonsCredit.plain(image.artistHtml)}, via Wikimedia Commons"
                columns["image_licence_id"] = checkNotNull(CommonsLicences.spdxOf(image.licenceShortName))
                columns["image_source_url"] = checkNotNull(image.descriptionUrl)
                fields += "image"
            }
        }
        return Filled(columns, fields, refusal)
    }

    /**
     * Why a Commons file may not be stored, or null when it may. All four credit columns or none
     * (V020), so a licence the map does not know or an author Commons could not name refuses the
     * whole picture rather than storing one without its credit.
     */
    private fun refuse(
        image: CommonsImage,
        maxBytes: Long
    ): String? =
        when {
            CommonsLicences.spdxOf(image.licenceShortName) == null -> "licence"
            !CommonsCredit.namesAnAuthor(CommonsCredit.plain(image.artistHtml)) -> "author"
            image.descriptionUrl.isNullOrBlank() -> "source"
            image.mime?.startsWith("image/") != true -> "mime"
            image.servedOriginal && image.size > maxBytes -> "size"
            else -> null
        }

    private fun linksOf(entity: MusicBrainzArtist): Map<Link, String> {
        val links = mutableMapOf<Link, String>()
        entity.relations.filter { it.isLive }.forEach { relation ->
            val link = linkOf(relation) ?: return@forEach
            links.putIfAbsent(link, relation.url.resource)
        }
        return links
    }

    /** The column a relationship fills: by its type where the type is the site, by host where the type is a family. */
    private fun linkOf(relation: MusicBrainzUrlRelation): Link? =
        BY_TYPE[relation.type] ?: BY_HOST[relation.type]?.let { hosts ->
            val host = hostOf(relation.url.resource)
            hosts.entries.firstOrNull { (domain, _) -> host.isOn(domain) }?.value
        }

    private fun hostOf(url: String): String =
        runCatching { URI(url).host }
            .getOrNull()
            .orEmpty()
            .lowercase()
            .removePrefix("www.")

    private fun String.isOn(domain: String): Boolean = this == domain || endsWith(".$domain")

    private val MusicBrainzUrlRelation.isLive: Boolean get() = !ended

    private val WIKIDATA_ID = Regex("Q\\d+")

    private val BY_TYPE =
        mapOf(
            "official homepage" to Link.WEBSITE,
            "youtube" to Link.YOUTUBE,
            "bandcamp" to Link.BANDCAMP,
            "soundcloud" to Link.SOUNDCLOUD,
            "discogs" to Link.DISCOGS,
            "wikidata" to Link.WIKIDATA
        )

    private val BY_HOST =
        mapOf(
            "social network" to mapOf("facebook.com" to Link.FACEBOOK, "instagram.com" to Link.INSTAGRAM),
            "other databases" to mapOf("ra.co" to Link.RESIDENT_ADVISOR, "residentadvisor.net" to Link.RESIDENT_ADVISOR),
            "free streaming" to mapOf("open.spotify.com" to Link.SPOTIFY)
        )

    private enum class Link { WEBSITE, FACEBOOK, INSTAGRAM, YOUTUBE, BANDCAMP, SOUNDCLOUD, DISCOGS, WIKIDATA, RESIDENT_ADVISOR, SPOTIFY }
}
