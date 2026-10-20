package de.norm.events.importing

import de.norm.events.artist.artistSlugFor
import de.norm.events.event.EventEntity
import de.norm.events.event.PinnedField
import de.norm.events.genretag.nonGenreTokens
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.splitOnEmoji
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.stripTitleStatusMarker
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import org.springframework.stereotype.Service

/**
 * Resolves artists, promoters and genre tags by slug, auto-creating unknown ones, and
 * synchronizes the join-table associations for upserted events by diff: insert new, update
 * changed (artist role/billing order), delete stale. Called within the caller's transaction.
 * Each kind is its own step ([ArtistAssociationSync], [PromoterAssociationSync], [GenreTagAssociationSync]);
 * this class decides which events each step sees, and keeps a pinned join table (ADR-042).
 *
 * It is also the gate: a value it keeps out of an event is recorded as an [EventQualityFlag], which
 * the data-quality worklist lists (#320).
 */
@Service
class AssociationSyncService(
    private val artistSync: ArtistAssociationSync,
    private val promoterSync: PromoterAssociationSync,
    private val genreTagSync: GenreTagAssociationSync,
    private val qualityFlagRepository: EventQualityFlagRepository,
    private val genreDerivation: GenreDerivationService
) {
    private val logger = KotlinLogging.logger {}

    /**
     * The single entry point [EventUpsertService] calls after upserting: resolve artists, diff-sync
     * their associations, the same for promoters, then normalized genre tags. A join table an event
     * pins keeps its stored rows (ADR-042).
     *
     * @param savedEvents the persisted event entities (non-null IDs).
     * @param scrapedEvents the raw scraped events.
     * @param keepStored the join tables of each `sourceId` that an enrichment source filled and this
     *   source leaves empty. They keep their stored rows as a pinned one does, and count as no pin (ADR-043).
     * @return the artist rows for the MusicBrainz sweep, and how many pinned join tables the source would have changed.
     */
    suspend fun resolveAndSyncAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        keepStored: Map<String, Set<PinnedField>> = emptyMap()
    ): AssociationOutcome {
        val events = withOwnedLineupsKept(savedEvents, scrapedEvents)
        val (allBilled, heldBack) = artistSync.billedArtists(events)
        val unverified = heldBack.values.flatten().distinctBy { artistSlugFor(it.name) }
        val detailless = events.filter { it.detailUnavailable }.mapTo(mutableSetOf()) { it.sourceId }
        val pins = AssociationPins(savedEvents, detailless)
        val pinsKept = keptPins(pins, allBilled, events)
        val kept = { field: PinnedField -> pins.sourceIds(field) + keepStored.filterValues { field in it }.keys }
        val synced = { field: PinnedField -> savedEvents.filterNot { it.sourceId in kept(field) } }

        // A pinned join table is neither resolved nor synced, so the source's names create no rows for it.
        val lineupPinned = kept(PinnedField.LINEUP)
        val promotersPinned = kept(PinnedField.PROMOTERS)
        val genresPinned = kept(PinnedField.GENRES)
        val billed = allBilled.filterKeys { it !in lineupPinned }
        val artistCache = artistSync.resolveAllArtists(billed.values.flatten() + unverified)
        artistSync.syncArtistAssociations(synced(PinnedField.LINEUP), billed, artistCache, detailless)

        val promoterEvents = events.map { if (it.sourceId in promotersPinned) it.copy(promoters = emptyList()) else it }
        val promoterCache = promoterSync.resolveAllPromoters(promoterEvents)
        promoterSync.syncPromoterAssociations(synced(PinnedField.PROMOTERS), promoterEvents, promoterCache, detailless)

        val genreEvents = events.map { if (it.sourceId in genresPinned) it.copy(genre = null) else it }
        val genreTagCache = genreTagSync.resolveAllGenreTags(genreEvents)
        genreTagSync.syncGenreTagAssociations(synced(PinnedField.GENRES), genreEvents, genreTagCache)
        genreDerivation.derive(synced(PinnedField.GENRES))

        // A pinned field ignores the source, so nothing the source published there is flagged.
        val flagEvents = genreEvents.map { if (it.sourceId in lineupPinned) it.copy(artists = emptyList()) else it }
        syncQualityFlags(savedEvents, flagEvents, heldBack.filterKeys { it !in lineupPinned })
        return AssociationOutcome(touchedArtistIds = artistCache.values.mapNotNullTo(mutableSetOf()) { it.id }, pinsKept = pinsKept)
    }

    /**
     * Fills the join tables an enrichment source may fill and [events] leave empty (ADR-043 rule 4):
     * the lineup, the promoters, and the genre tags where the genre column was filled in the same run.
     * A pinned table, or one that holds a row, is not touched, and the sync never deletes: it is
     * given only events whose stored rows are none. The gate runs as for a main source, but writes no
     * flags: those describe the main source's import of the event.
     *
     * @param events each stored main event beside the enrichment event that matched it, keyed by the
     *   main event's `sourceId`.
     * @param genreFilled the ids of the events whose genre column this run filled.
     * @return the join tables filled per event id, and the artist rows for the MusicBrainz sweep.
     */
    suspend fun fillEmptyAssociations(
        events: List<Pair<EventEntity, ScrapedEvent>>,
        genreFilled: Set<Long>
    ): AssociationFill {
        val ids = events.mapNotNull { it.first.id }
        if (ids.isEmpty()) return AssociationFill(emptyMap(), emptySet())
        val withLineup = artistSync.eventIdsWithLineup(ids)
        val withPromoters = promoterSync.eventIdsWithPromoters(ids)
        val withGenres = genreTagSync.eventIdsWithSourceTags(ids)
        val open = { field: PinnedField, filled: Set<Long> ->
            events.filter { (stored, _) -> stored.id !in filled && field.key !in stored.pinnedFields }
        }

        val lineupEvents = open(PinnedField.LINEUP, withLineup)
        val (billed, heldBack) = artistSync.billedArtists(lineupEvents.map { it.second })
        val artistCache = artistSync.resolveAllArtists(billed.values.flatten() + heldBack.values.flatten().distinctBy { artistSlugFor(it.name) })
        val lineupFilled = lineupEvents.filter { (stored, _) -> billed[stored.sourceId].orEmpty().isNotEmpty() }.map { it.first }
        artistSync.syncArtistAssociations(lineupFilled, billed, artistCache, emptySet())

        val promoterEvents = open(PinnedField.PROMOTERS, withPromoters).filter { (_, scraped) -> scraped.promoterSlugs().isNotEmpty() }
        val promoterCache = promoterSync.resolveAllPromoters(promoterEvents.map { it.second })
        promoterSync.syncPromoterAssociations(promoterEvents.map { it.first }, promoterEvents.map { it.second }, promoterCache, emptySet())

        val genreEvents =
            open(PinnedField.GENRES, withGenres)
                .filter { (stored, scraped) -> stored.id in genreFilled && normalizeGenre(scraped.storedGenre()).isNotEmpty() }
        val genreTagCache = genreTagSync.resolveAllGenreTags(genreEvents.map { it.second })
        genreTagSync.syncGenreTagAssociations(genreEvents.map { it.first }, genreEvents.map { it.second }, genreTagCache)

        val filled =
            listOf(
                PinnedField.LINEUP to lineupFilled,
                PinnedField.PROMOTERS to promoterEvents.map { it.first },
                PinnedField.GENRES to genreEvents.map { it.first }
            ).flatMap { (field, rows) -> rows.mapNotNull { it.id }.map { it to field } }
                .groupBy({ it.first }, { it.second })
                .mapValues { it.value.toSet() }
        return AssociationFill(filled, artistCache.values.mapNotNullTo(mutableSetOf()) { it.id })
    }

    // -- Pinned join tables (ADR-042) --

    /**
     * How many pinned join tables the source would have changed, by the slugs of their rows. Each one
     * is logged at DEBUG. A role or billing change alone is not counted.
     */
    private suspend fun keptPins(
        pins: AssociationPins,
        billed: Map<String, List<ScrapedArtist>>,
        events: List<ScrapedEvent>
    ): Int {
        if (pins.isEmpty()) return 0
        val bySourceId = events.associateBy { it.sourceId }
        val lineup = pins.pinned(PinnedField.LINEUP)
        val promoters = pins.pinned(PinnedField.PROMOTERS)
        val genres = pins.pinned(PinnedField.GENRES)
        return pins.countChanged(
            PinnedField.LINEUP,
            desired =
                artistSync.renamedArtistSlugs(
                    lineup.associate { it.sourceId to billed[it.sourceId].orEmpty().map { a -> artistSlugFor(a.name) }.toSet() }
                ),
            stored = artistSync.storedSlugs(lineup)
        ) +
            pins.countChanged(
                PinnedField.PROMOTERS,
                desired = promoters.associate { it.sourceId to bySourceId[it.sourceId]?.promoterSlugs().orEmpty() },
                stored = promoterSync.storedSlugs(promoters)
            ) +
            pins.countChanged(
                PinnedField.GENRES,
                desired = genres.associate { it.sourceId to normalizeGenre(bySourceId[it.sourceId]?.genre).map(SlugGenerator::slugify).toSet() },
                stored = genreTagSync.storedSlugs(genres)
            )
    }

    /**
     * Which saved events pin which join table. A detail-less event whose listing names nothing keeps
     * its stored rows anyway (#2421), so the count does not report it.
     */
    private inner class AssociationPins(
        private val savedEvents: List<EventEntity>,
        private val detailless: Set<String>
    ) {
        private val pinsBySourceId = savedEvents.associate { it.sourceId to PinnedField.of(it.pinnedFields) }

        fun isEmpty(): Boolean = pinsBySourceId.values.all { it.isEmpty() }

        fun pinned(field: PinnedField): List<EventEntity> = savedEvents.filter { field in pinsBySourceId.getValue(it.sourceId) }

        fun unpinned(field: PinnedField): List<EventEntity> = savedEvents.filterNot { field in pinsBySourceId.getValue(it.sourceId) }

        fun sourceIds(field: PinnedField): Set<String> = pinned(field).mapTo(mutableSetOf()) { it.sourceId }

        fun countChanged(
            field: PinnedField,
            desired: Map<String, Set<String>>,
            stored: Map<Long, Set<String>>
        ): Int =
            pinned(field).count { event ->
                val wanted = desired[event.sourceId].orEmpty()
                val changed = wanted != stored[event.id].orEmpty() && !(wanted.isEmpty() && event.sourceId in detailless)
                if (changed) {
                    logger.at(Level.DEBUG) {
                        message = "Kept the pinned ${field.key} of event '${event.title}': the source would change it"
                        payload = mapOf(LogFields.EVENT_ID to event.id, LogFields.EVENT_SOURCE_ID to event.sourceId)
                    }
                }
                changed
            }
    }

    /**
     * [scraped] without the listing's acts on each failed-page row whose page owns the lineup
     * ([ScrapedField.ARTISTS]) and whose stored lineup is not empty. [keepsStoredLineup] then keeps the
     * stored acts, and no act only the coarser listing names is created (#2542).
     */
    private suspend fun withOwnedLineupsKept(
        savedEvents: List<EventEntity>,
        scraped: List<ScrapedEvent>
    ): List<ScrapedEvent> {
        val owning =
            scraped
                .filter { it.detailUnavailable && ScrapedField.ARTISTS in it.detailPageOwns && it.artists.isNotEmpty() }
                .map { it.sourceId }
                .toSet()
        val eventIds = savedEvents.filter { it.sourceId in owning }.mapNotNull { it.id }
        if (eventIds.isEmpty()) return scraped
        val withLineup = artistSync.eventIdsWithLineup(eventIds)
        val kept = savedEvents.filter { it.id in withLineup }.map { it.sourceId }.toSet()
        if (kept.isNotEmpty()) logger.info { "Kept the stored lineup of ${kept.size} event(s): their detail page yielded nothing" }
        return scraped.map { if (it.sourceId in kept) it.copy(artists = emptyList()) else it }
    }
    // -- The gate's flags --

    /**
     * Replaces the flags of every saved event with what the gate kept out of it in this run. A value
     * the venue no longer publishes leaves the worklist with the next import.
     */
    private suspend fun syncQualityFlags(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        heldBack: Map<String, List<ScrapedArtist>>
    ) {
        val eventIdBySourceId = savedEvents.mapNotNull { saved -> saved.id?.let { saved.sourceId to it } }.toMap()
        val flags =
            scrapedEvents.flatMap { event ->
                val eventId = eventIdBySourceId[event.sourceId] ?: return@flatMap emptyList()
                gateFlags(event, heldBack[event.sourceId].orEmpty()).map { (kind, value) -> EventQualityFlag(eventId, kind, value) }
            }
        qualityFlagRepository.replaceFor(eventIdBySourceId.values, flags)
        if (flags.isNotEmpty()) {
            logger.info { "Flagged ${flags.size} value(s) for the data-quality worklist: ${flags.groupingBy { it.kind }.eachCount()}" }
        }
    }

    /**
     * What the gate keeps out of [event]: refused artist names as the venue billed them, the
     * [heldBack] promoter names, a genre that repeats the title, and the genre words that name no genre.
     */
    private fun gateFlags(
        event: ScrapedEvent,
        heldBack: List<ScrapedArtist>
    ): List<Pair<QualityFlagKind, String>> {
        val artists =
            event.artists
                .map { it.copy(name = stripTitleStatusMarker(it.name)) }
                .flatMap(::splitGuest)
                .flatMap { artist -> splitOnEmoji(artist) }
                .flatMap { artist -> splitBackToBack(artist) }
                .mapNotNull { artist -> artistRefusal(stripArtistSuffix(artist.name))?.let { it to artist.name.trim() } }
        val promoters = heldBack.map { QualityFlagKind.HELD_BACK_PROMOTER_NAME to it.name.trim() }
        val genre = event.genre?.takeIf { event.genreRepeatsTitle() }?.let { QualityFlagKind.GENRE_EQUALS_TITLE to it.trim() }
        val nonGenres = nonGenreTokens(event.storedGenre()).map { QualityFlagKind.NON_GENRE_TOKEN to it }
        return artists + promoters + listOfNotNull(genre) + nonGenres
    }
}

/** What [AssociationSyncService.resolveAndSyncAssociations] did that the upsert reports. */
data class AssociationOutcome(
    /**
     * Every artist row this run billed, new or existing, for the MusicBrainz sweep after the commit
     * (#1567), and every row held back unbilled until that sweep can vouch for it (#1841).
     */
    val touchedArtistIds: Set<Long>,
    /** Pinned join tables the source would have changed (ADR-042). */
    val pinsKept: Int = 0
)

/** What [AssociationSyncService.fillEmptyAssociations] filled. */
data class AssociationFill(
    /** The join tables filled, by event id. */
    val filled: Map<Long, Set<PinnedField>>,
    /** The artist rows the fill billed or held back, for the MusicBrainz sweep after the commit (#1567). */
    val touchedArtistIds: Set<Long>
)
