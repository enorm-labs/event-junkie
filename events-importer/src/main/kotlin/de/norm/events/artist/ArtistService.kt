package de.norm.events.artist

import de.norm.events.common.PageResponse
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service

/**
 * Service encapsulating artist business logic.
 *
 * Slugs are always auto-generated from the artist name using [SlugGenerator].
 */
@Service
class ArtistService(
    private val artistRepository: ArtistRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Lists artists with pagination and sorting.
     *
     * Pagination and sorting are controlled by the [pageable] parameter, which Spring
     * resolves from `page`, `size`, and `sort` query parameters
     * (e.g. `?page=0&size=20&sort=name,asc`).
     *
     * The total comes from a separate `count()`, which is exact because this listing takes no
     * filter. It is what lets a caller tell one page from the whole table (#810).
     */
    suspend fun findAll(pageable: Pageable): PageResponse<ArtistResponse> =
        PageResponse.of(
            artistRepository.findAllBy(pageable).map { ArtistResponse.fromDomain(it.toDomain()) }.toList(),
            pageable,
            artistRepository.count()
        )

    /**
     * Finds a single artist by [id].
     *
     * @throws ArtistNotFoundException if no artist with the given [id] exists.
     */
    suspend fun findById(id: Long): ArtistResponse = ArtistResponse.fromDomain(artistRepository.findById(id)?.toDomain() ?: throw ArtistNotFoundException(id))

    /**
     * The slug is auto-generated from the artist name
     * (e.g. `"The Adicts"` → `"the-adicts"`).
     */
    suspend fun create(request: ArtistRequest): ArtistResponse {
        val slug = SlugGenerator.slugify(request.name)
        // Pre-check for slug uniqueness to provide a clear error message instead of
        // relying on the DB constraint, which would produce a generic 409 response.
        ensureSlugAvailable(request.name, slug)

        // Route through the domain model to ensure any future business rules
        // (e.g. validation, normalization) are consistently applied (see ADR-003).
        val artist =
            Artist(
                name = request.name,
                slug = slug,
                description = request.description,
                imageUrl = request.imageUrl,
                imageAttribution = request.imageAttribution,
                imageLicenceId = request.imageLicenceId,
                imageSourceUrl = request.imageSourceUrl,
                websiteUrl = request.websiteUrl,
                facebookUrl = request.facebookUrl,
                instagramUrl = request.instagramUrl,
                youtubeUrl = request.youtubeUrl,
                bandcampUrl = request.bandcampUrl,
                soundcloudUrl = request.soundcloudUrl,
                discogsUrl = request.discogsUrl,
                wikidataUrl = request.wikidataUrl,
                residentAdvisorUrl = request.residentAdvisorUrl,
                spotifyUrl = request.spotifyUrl
            )
        val entity = ArtistEntity.fromDomain(artist)
        val saved = artistRepository.save(entity)
        logger.info { "Created artist '${saved.name}' with id ${saved.id}" }
        return ArtistResponse.fromDomain(storeHandSetMusicBrainzId(saved, request.musicbrainzId).toDomain())
    }

    /**
     * Replaces all mutable fields of an existing artist. A changed name is pinned, so the MusicBrainz
     * enrichment keeps it (ADR-042).
     *
     * @throws ArtistNotFoundException if no artist with the given [id] exists.
     */
    suspend fun update(
        id: Long,
        request: ArtistRequest
    ): ArtistResponse {
        val existing =
            artistRepository.findById(id)
                ?: throw ArtistNotFoundException(id)

        val slug = SlugGenerator.slugify(request.name)
        // Only check for conflicts if the slug actually changed — renaming to the same
        // effective slug (e.g. fixing capitalization) should not trigger a false positive.
        if (slug != existing.slug) {
            ensureSlugAvailable(request.name, slug)
        }

        val pinsName = request.name != existing.name
        // A licensed text keeps its language, credit and other-language lead only while it is unchanged; an edited text is the editor's own.
        val keepsDescription = request.description == existing.description
        val updated =
            existing.copy(
                name = request.name,
                slug = slug,
                description = request.description,
                descriptionLanguage = existing.descriptionLanguage.takeIf { keepsDescription },
                descriptionAttribution = existing.descriptionAttribution.takeIf { keepsDescription },
                descriptionLicenceId = existing.descriptionLicenceId.takeIf { keepsDescription },
                descriptionSourceUrl = existing.descriptionSourceUrl.takeIf { keepsDescription },
                descriptionAlt = existing.descriptionAlt.takeIf { keepsDescription },
                descriptionAltLanguage = existing.descriptionAltLanguage.takeIf { keepsDescription },
                descriptionAltAttribution = existing.descriptionAltAttribution.takeIf { keepsDescription },
                descriptionAltLicenceId = existing.descriptionAltLicenceId.takeIf { keepsDescription },
                descriptionAltSourceUrl = existing.descriptionAltSourceUrl.takeIf { keepsDescription },
                imageUrl = request.imageUrl,
                imageAttribution = request.imageAttribution,
                imageLicenceId = request.imageLicenceId,
                imageSourceUrl = request.imageSourceUrl,
                websiteUrl = request.websiteUrl,
                facebookUrl = request.facebookUrl,
                instagramUrl = request.instagramUrl,
                youtubeUrl = request.youtubeUrl,
                bandcampUrl = request.bandcampUrl,
                soundcloudUrl = request.soundcloudUrl,
                discogsUrl = request.discogsUrl,
                wikidataUrl = request.wikidataUrl,
                residentAdvisorUrl = request.residentAdvisorUrl,
                spotifyUrl = request.spotifyUrl,
                namePinned = existing.namePinned || pinsName
            )
        val saved = artistRepository.save(updated)
        logger.info { "Updated artist '${saved.name}' (id=${saved.id})" }
        if (pinsName) logger.info { "Pinned the name of artist $id: the MusicBrainz enrichment keeps it" }
        return ArtistResponse.fromDomain(storeHandSetMusicBrainzId(saved, request.musicbrainzId).toDomain())
    }

    /**
     * Stores a MusicBrainz id set by hand as an EXACT verdict, for a name MusicBrainz gives several artists (#2827).
     * A separate write after the save, so its database clock is later than a rename's `name_changed_at`, and the
     * sweep does not look the name up again. A null [mbid], or the one already stored, changes nothing.
     */
    private suspend fun storeHandSetMusicBrainzId(
        saved: ArtistEntity,
        mbid: String?
    ): ArtistEntity {
        val id = saved.id
        val alreadyStored = saved.musicbrainzId == mbid && saved.musicbrainzMatch == MusicBrainzMatch.EXACT.name
        if (mbid == null || id == null || alreadyStored) return saved
        artistRepository.storeMusicBrainzVerdict(id, MusicBrainzMatch.EXACT.name, mbid)
        logger.info { "Set the MusicBrainz id of artist $id by hand: $mbid" }
        return artistRepository.findById(id) ?: throw ArtistNotFoundException(id)
    }

    /**
     * Removes the pin a hand edit set on the name of artist [id] (ADR-042). The row is queued for
     * the enrichment again, which then writes MusicBrainz's letter case on an EXACT match. Removing
     * a pin the row does not carry changes nothing.
     *
     * @throws ArtistNotFoundException if no artist with the given [id] exists.
     */
    suspend fun unpinName(id: Long) {
        val existing = artistRepository.findById(id) ?: throw ArtistNotFoundException(id)
        if (!existing.namePinned) return
        artistRepository.save(existing.copy(namePinned = false, musicbrainzEnrichedAt = null))
        logger.info { "Unpinned the name of artist $id: the next MusicBrainz enrichment may change its letter case" }
    }

    /**
     * Deletes an artist by [id].
     *
     * @throws ArtistNotFoundException if no artist with the given [id] exists.
     */
    suspend fun delete(id: Long) {
        if (!artistRepository.existsById(id)) throw ArtistNotFoundException(id)
        // Join table rows (event_artist) are cascade-deleted by the database FK constraint (ON DELETE CASCADE).
        artistRepository.deleteById(id)
        logger.info { "Deleted artist with id $id" }
    }

    /**
     * Checks whether the given [slug] is already taken by another artist and throws
     * [DuplicateArtistSlugException] if so. This provides a clear, domain-specific error
     * message to the API consumer instead of relying on the DB's generic constraint violation.
     */
    private suspend fun ensureSlugAvailable(
        name: String,
        slug: String
    ) {
        if (artistRepository.findBySlug(slug) != null) {
            throw DuplicateArtistSlugException(name, slug)
        }
    }
}
