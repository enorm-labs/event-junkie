package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.artist.artistSlugFor
import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.event.EventType
import de.norm.events.event.PinnedField
import de.norm.events.genretag.EventGenreTagEntity
import de.norm.events.genretag.EventGenreTagRepository
import de.norm.events.genretag.GenreTagEntity
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.genretag.genreFamily
import de.norm.events.genretag.nonGenreTokens
import de.norm.events.genretag.normalizeGenre
import de.norm.events.musicbrainz.MusicBrainzMatcher
import de.norm.events.promoter.PromoterEntity
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.canonicalPromoterName
import de.norm.events.promoter.isNonPromoterName
import de.norm.events.scraper.LogFields
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.ScrapedField
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isSlugless
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.splitBracketedGuest
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.Level
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Service
import java.net.URI
import java.text.Normalizer

/**
 * Resolves artists, promoters and genre tags by slug, auto-creating unknown ones, and
 * synchronizes the join-table associations for upserted events by diff: insert new, update
 * changed (artist role/billing order), delete stale. Called within the caller's transaction.
 *
 * It is also the gate: a value it keeps out of an event is recorded as an [EventQualityFlag], which
 * the data-quality worklist lists (#320).
 */
@Service
@Suppress(
    "TooManyFunctions", // Logically cohesive — groups artist, promoter, and genre tag association management
    "LongParameterList" // One repository per table the sync writes
)
class AssociationSyncService(
    private val eventArtistRepository: EventArtistRepository,
    private val eventPromoterRepository: EventPromoterRepository,
    private val eventGenreTagRepository: EventGenreTagRepository,
    private val artistRepository: ArtistRepository,
    private val promoterRepository: PromoterRepository,
    private val genreTagRepository: GenreTagRepository,
    private val qualityFlagRepository: EventQualityFlagRepository
) {
    private val logger = KotlinLogging.logger {}

    /**
     * The single entry point [EventUpsertService] calls after upserting: resolve artists, diff-sync
     * their associations, the same for promoters, then normalized genre tags. A join table an event
     * pins keeps its stored rows (ADR-042).
     *
     * @param savedEvents the persisted event entities (non-null IDs).
     * @param scrapedEvents the raw scraped events.
     * @return the artist rows for the MusicBrainz sweep, and how many pinned join tables the source would have changed.
     */
    suspend fun resolveAndSyncAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>
    ): AssociationOutcome {
        val events = withOwnedLineupsKept(savedEvents, scrapedEvents)
        val (allBilled, heldBack) = billedArtists(events)
        val unverified = heldBack.values.flatten().distinctBy { slugOf(it.name) }
        val detailless = events.filter { it.detailUnavailable }.mapTo(mutableSetOf()) { it.sourceId }
        val pins = AssociationPins(savedEvents, detailless)
        val pinsKept = keptPins(pins, allBilled, events)

        // A pinned join table is neither resolved nor synced, so the source's names create no rows for it.
        val lineupPinned = pins.sourceIds(PinnedField.LINEUP)
        val promotersPinned = pins.sourceIds(PinnedField.PROMOTERS)
        val genresPinned = pins.sourceIds(PinnedField.GENRES)
        val billed = allBilled.filterKeys { it !in lineupPinned }
        val artistCache = resolveAllArtists(billed.values.flatten() + unverified)
        syncArtistAssociations(pins.unpinned(PinnedField.LINEUP), billed, artistCache, detailless)

        val promoterEvents = events.map { if (it.sourceId in promotersPinned) it.copy(promoters = emptyList()) else it }
        val promoterCache = resolveAllPromoters(promoterEvents)
        syncPromoterAssociations(pins.unpinned(PinnedField.PROMOTERS), promoterEvents, promoterCache, detailless)

        val genreEvents = events.map { if (it.sourceId in genresPinned) it.copy(genre = null) else it }
        val genreTagCache = resolveAllGenreTags(genreEvents)
        syncGenreTagAssociations(pins.unpinned(PinnedField.GENRES), genreEvents, genreTagCache)

        // A pinned field ignores the source, so nothing the source published there is flagged.
        val flagEvents = genreEvents.map { if (it.sourceId in lineupPinned) it.copy(artists = emptyList()) else it }
        syncQualityFlags(savedEvents, flagEvents, heldBack.filterKeys { it !in lineupPinned })
        return AssociationOutcome(touchedArtistIds = artistCache.values.mapNotNullTo(mutableSetOf()) { it.id }, pinsKept = pinsKept)
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
            desired = lineup.associate { it.sourceId to billed[it.sourceId].orEmpty().map { a -> slugOf(a.name) }.toSet() },
            stored =
                storedSlugs(lineup, { eventArtistRepository.findByEventIdIn(it).toList() }, { it.eventId to it.artistId }) { ids ->
                    artistRepository.findAllById(ids).toList().associate { it.id to it.slug }
                }
        ) +
            pins.countChanged(
                PinnedField.PROMOTERS,
                desired = promoters.associate { it.sourceId to bySourceId[it.sourceId]?.promoterSlugs().orEmpty() },
                stored =
                    storedSlugs(promoters, { eventPromoterRepository.findByEventIdIn(it).toList() }, { it.eventId to it.promoterId }) { ids ->
                        promoterRepository.findAllById(ids).toList().associate { it.id to it.slug }
                    }
            ) +
            pins.countChanged(
                PinnedField.GENRES,
                desired = genres.associate { it.sourceId to normalizeGenre(bySourceId[it.sourceId]?.genre).map(SlugGenerator::slugify).toSet() },
                stored =
                    storedSlugs(genres, { eventGenreTagRepository.findByEventIdIn(it).toList() }, { it.eventId to it.genreTagId }) { ids ->
                        genreTagRepository.findAllById(ids).toList().associate { it.id to it.slug }
                    }
            )
    }

    /** The slugs of the rows each of [events] links, by event id: one query for the links, one for the names. */
    private suspend fun <T> storedSlugs(
        events: List<EventEntity>,
        fetchLinks: suspend (List<Long>) -> List<T>,
        link: (T) -> Pair<Long, Long>,
        fetchSlugs: suspend (Set<Long>) -> Map<Long?, String>
    ): Map<Long, Set<String>> {
        if (events.isEmpty()) return emptyMap()
        val links = fetchLinks(events.mapNotNull { it.id }).map(link)
        val slugById = fetchSlugs(links.mapTo(mutableSetOf()) { it.second })
        return links.groupBy({ it.first }, { slugById[it.second] }).mapValues { (_, slugs) -> slugs.filterNotNull().toSet() }
    }

    /** The promoters this event credits, as the slugs [syncPromoterAssociations] resolves them to. */
    private fun ScrapedEvent.promoterSlugs(): Set<String> =
        promoters.filterNot { isNonPromoterName(it) }.mapTo(mutableSetOf()) { SlugGenerator.slugify(canonicalPromoterName(it)) }

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
        val withLineup =
            eventArtistRepository
                .findByEventIdIn(eventIds)
                .toList()
                .map { it.eventId }
                .toSet()
        val kept = savedEvents.filter { it.id in withLineup }.map { it.sourceId }.toSet()
        if (kept.isNotEmpty()) logger.info { "Kept the stored lineup of ${kept.size} event(s): their detail page yielded nothing" }
        return scraped.map { if (it.sourceId in kept) it.copy(artists = emptyList()) else it }
    }

    // -- Artist resolution --

    /**
     * Batch-fetches known artists by slug and auto-creates the rest.
     *
     * @return artist slug to persisted [ArtistEntity] for every artist the scraped events reference.
     */
    private suspend fun resolveAllArtists(scrapedArtists: List<ScrapedArtist>): Map<String, ArtistEntity> {
        // Canonicalize before slugging, as the promoter path does: a curated NAME_CORRECTIONS entry can
        // change the slug ("OXO86" resolves to "Oxo 86", `oxo-86`), and slugging the raw name would look
        // up a different row than resolveOrCreateArtist creates.
        val allArtistSlugs = scrapedArtists.map { artistSlugFor(it.name) }.toSet()
        val artistCache =
            artistRepository
                .findBySlugIn(allArtistSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        warnAboutAccentOnlyMatches(scrapedArtists, artistCache)

        // Auto-create only the artists not in the database, with the display name canonicalized first so
        // an act is not frozen SHOUTING by whichever venue imported it first.
        scrapedArtists
            .distinctBy { artistSlugFor(it.name) }
            .forEach { resolveOrCreateArtist(canonicalArtistName(it.name), artistCache) }

        return artistCache
    }

    /**
     * The artists each event bills, by `sourceId`, as they will be stored — computed once so the
     * resolution and the association diff cannot disagree about a name.
     *
     * The suffix an act does not own comes off here, for every source: `C3D-E (live)`, `Avangelic
     * (DJ-Set)` and `Regis Live & DJ set` are the same rows as `C3D-E`, `Avangelic` and `Regis`
     * imported from anywhere else, and sixteen line-up scrapers never called [stripArtistSuffix]
     * (#301). The model keeps no format, so nothing is lost that could have been stored. A name that
     * slugs to nothing has escaped [isNonArtistName], and would take the empty slug every later one
     * collides with (#1553). A headliner read off a title the boundary resolves to a festival is the
     * festival's name, not an act (`ELLE & L's Festival` → `Elle`, #300): undone here, once, while a
     * published line-up stays. A guest in a bracket is split off first ([splitBracketedGuest]), then a
     * `b2b` slot into its DJs ([splitBackToBack]), for every source.
     *
     * **A title-derived name the same event credits as its promoter is the series, not an act**
     * (#1772): Astra's secret-lineup night is titled `UNRELEASED BERLIN` and credits `Unreleased
     * Berlin` in its promoter block, so the title-as-headliner default minted the promoter. A
     * billed act is never dropped this way — only a name no line-up stated. **A band can promote its
     * own show too** (Urban Spree credits `WISBORG` for `WISBORG Phantomschmerz Tour`, #1841), and
     * the title cannot tell the two apart, so a MusicBrainz `EXACT` row for the name keeps it billed.
     * A name with no such row is held back: its row is created unbilled and returned for the sweep
     * after the commit, and the orphan sweep's grace day outlasts the next daily import. Then
     * [unglueSeriesTails].
     *
     * @return the billed artists by `sourceId`, and the held-back names by `sourceId`.
     */
    private suspend fun billedArtists(scrapedEvents: List<ScrapedEvent>): Pair<Map<String, List<ScrapedArtist>>, Map<String, List<ScrapedArtist>>> {
        scrapedEvents.forEach { event ->
            event.artists
                .filter { isSlugless(it.name) }
                .forEach { logger.warn { "Dropping artist '${it.name}' of '${event.sourceId}': its name slugs to nothing" } }
        }
        val stripped =
            scrapedEvents.associate { event ->
                val festival = event.resolvedEventType() == EventType.FESTIVAL
                event.sourceId to
                    event.artists
                        .flatMap { artist -> splitGuest(artist) }
                        .flatMap { artist -> splitBackToBack(artist) }
                        .map { it.copy(name = stripArtistSuffix(it.name)) }
                        .filterNot { refusal(it.name) != null || (it.titleDerived && festival) }
            }
        val promoterSlugsById = scrapedEvents.associate { event -> event.sourceId to event.promoters.map(::slugOf).toSet() }
        val promoterNamed =
            stripped.flatMap { (sourceId, artists) ->
                artists.filter { it.titleDerived && slugOf(it.name) in promoterSlugsById.getValue(sourceId) }
            }
        val verified = exactSlugs(promoterNamed.map { slugOf(it.name) }.toSet())
        val heldBack =
            stripped
                .mapValues { (sourceId, artists) ->
                    artists.filter { it.titleDerived && slugOf(it.name) in promoterSlugsById.getValue(sourceId) && slugOf(it.name) !in verified }
                }.filterValues { it.isNotEmpty() }
        val billed = stripped.mapValues { (sourceId, artists) -> artists - heldBack[sourceId].orEmpty().toSet() }
        heldBack.values.flatten().distinctBy { slugOf(it.name) }.forEach {
            logger.info { "Holding back '${it.name}': the event's promoter, and no MusicBrainz EXACT row vouches for it as an act" }
        }
        return unglueSeriesTails(billed) to heldBack
    }

    /** The subset of [slugs] whose artist row MusicBrainz matched `EXACT`. */
    private suspend fun exactSlugs(slugs: Set<String>): Set<String> =
        if (slugs.isEmpty()) {
            emptySet()
        } else {
            artistRepository
                .findBySlugIn(slugs)
                .toList()
                .filter { it.musicbrainzMatch == MusicBrainzMatch.EXACT.name }
                .mapTo(mutableSetOf()) { it.slug }
        }

    /** Why the gate keeps [name] off a lineup, or null when the name may stay. */
    private fun refusal(name: String): QualityFlagKind? =
        when {
            isSlugless(name) -> QualityFlagKind.SLUGLESS_ARTIST
            isNonArtistName(name) -> QualityFlagKind.NON_ARTIST_NAME
            else -> null
        }

    /** [splitBracketedGuest] on one billing; a headliner's guest is support, as a `feat.` title bills it (#305). */
    private fun splitGuest(artist: ScrapedArtist): List<ScrapedArtist> =
        splitBracketedGuest(artist.name).mapIndexed { index, name ->
            val role = if (index > 0 && artist.role == "HEADLINER") "SUPPORT" else artist.role
            artist.copy(name = name, role = role)
        }

    /**
     * `Xmal Deutschland – Sonic Morgue` is `Xmal Deutschland` when the catalogue already holds
     * `Xmal Deutschland` as a MusicBrainz `EXACT` row and holds no such row for the glued name
     * (#302). The series names themselves are never listed: MusicBrainz is the vocabulary, and the
     * verified row is the evidence — a head whose row is absent, `UNCHECKED`, `AMBIGUOUS` or `NONE`
     * decides nothing, as ADR-031 rule 3 requires, and the glued name stays for the review queue. The
     * head is [MusicBrainzMatcher.headOf]'s: the part before ` - ` or `: `, never digits only. One
     * batched slug fetch for every dashed name in the run.
     */
    private suspend fun unglueSeriesTails(billed: Map<String, List<ScrapedArtist>>): Map<String, List<ScrapedArtist>> {
        val headByName =
            billed.values
                .flatten()
                .map { it.name }
                .distinct()
                .mapNotNull { name -> MusicBrainzMatcher.headOf(name)?.let { head -> name to head } }
                .toMap()
        val replacement = if (headByName.isEmpty()) emptyMap() else verifiedHeads(headByName)
        replacement.forEach { (glued, act) -> logger.info { "Storing '$glued' as '$act': the head is a verified MusicBrainz row" } }
        return billed.mapValues { (_, artists) -> artists.map { a -> replacement[a.name]?.let { a.copy(name = it) } ?: a } }
    }

    /** Glued name to the stored name of its head, for every head that is an `EXACT` row while the glued name is not. */
    private suspend fun verifiedHeads(headByName: Map<String, String>): Map<String, String> {
        val slugsToRead = headByName.flatMap { (name, head) -> listOf(slugOf(name), slugOf(head)) }.toSet()
        val exactBySlug =
            artistRepository
                .findBySlugIn(slugsToRead)
                .toList()
                .filter { it.musicbrainzMatch == MusicBrainzMatch.EXACT.name }
                .associateBy { it.slug }
        return headByName
            .mapNotNull { (name, head) ->
                val headRow = exactBySlug[slugOf(head)]
                if (headRow != null && exactBySlug[slugOf(name)] == null) name to headRow.name else null
            }.toMap()
    }

    private fun slugOf(name: String) = artistSlugFor(name)

    /**
     * A billed name that reaches a stored row whose name differs only by accents may be a different act, as `Göre`
     * reached the US band `Gore`. It may also be one act spelled two ways, so this warns and never splits (#2942).
     */
    private fun warnAboutAccentOnlyMatches(
        scrapedArtists: List<ScrapedArtist>,
        artistCache: Map<String, ArtistEntity>
    ) {
        scrapedArtists
            .map { canonicalArtistName(it.name) }
            .distinct()
            .forEach { billed ->
                val stored = artistCache[artistSlugFor(billed)] ?: return@forEach
                if (differsOnlyByAccents(billed, stored.name)) {
                    logger.warn {
                        "Billed '$billed' resolved to artist '${stored.name}' (${stored.slug}): the names differ only by accents. " +
                            "A different act needs an ARTIST_SLUG_OVERRIDES entry"
                    }
                }
            }
    }

    /** Resolves an artist by name from [artistCache], or auto-creates one. See [resolveOrCreate]. */
    private suspend fun resolveOrCreateArtist(
        name: String,
        artistCache: MutableMap<String, ArtistEntity>
    ): ArtistEntity =
        resolveOrCreate(
            name = name,
            cache = artistCache,
            insertIfAbsent = { slug -> artistRepository.insertIfAbsent(name, slug) },
            findBySlug = { slug -> artistRepository.findBySlug(slug) },
            slug = artistSlugFor(name)
        )

    // -- Artist association syncing --

    /**
     * Synchronizes artist associations by diff, matched on `(eventId, artistId)`: inserts new,
     * updates changed role, billing order, floor or set times, deletes removed, skips the rest. Deleting and
     * re-creating on every import wastes auto-increment IDs.
     *
     * An event in [detailless] keeps its stored lineup when the listing names no act, and an act's
     * stored set times when the listing gives none: the detail page is their source (#2421).
     */
    private suspend fun syncArtistAssociations(
        savedEvents: List<EventEntity>,
        artistsBySourceId: Map<String, List<ScrapedArtist>>,
        artistCache: Map<String, ArtistEntity>,
        detailless: Set<String>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventArtistRepository.findByEventIdIn(it).toList() },
                EventArtistEntity::eventId
            ) ?: return

        val toInsert = mutableListOf<EventArtistEntity>()
        val toUpdate = mutableListOf<EventArtistEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents.filterNot { keepsStoredLineup(it.sourceId, artistsBySourceId, detailless) }) {
            val (eventId, existingByArtistId, existing) =
                eventAssociationContext(saved, existingByEventId, EventArtistEntity::artistId)

            val desiredArtists = artistsBySourceId[saved.sourceId].orEmpty()
            val keepsSetTimes = saved.sourceId in detailless
            val desiredArtistIds = mutableSetOf<Long>()

            for ((index, scrapedArtist) in desiredArtists.withIndex()) {
                val slug = artistSlugFor(scrapedArtist.name)
                val artistId = requireNotNull(artistCache[slug]?.id) { "Artist '$slug' must be resolved before syncing associations" }

                // `add` returns false when this artist is already desired for this event, as when a lineup
                // names one act twice or two spellings canonicalise together. `existingByArtistId` reflects the
                // database and cannot catch that; the table's UNIQUE (event_id, artist_id) would reject the
                // batch and roll back the whole run (#798).
                if (!desiredArtistIds.add(artistId)) continue

                val desired = scrapedArtist.toEventArtistEntity(eventId, artistId, billingOrder = index)
                val current = existingByArtistId[artistId]
                if (current == null) {
                    toInsert.add(desired)
                } else {
                    // The row keeps its id; everything the scrape decides is compared at once.
                    val updated = desired.onRowOf(current, keepsSetTimes)
                    if (updated != current) toUpdate.add(updated)
                }
            }

            existing
                .filter { it.artistId !in desiredArtistIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventArtistRepository.deleteAllById(toDeleteIds)
        }
        if (toUpdate.isNotEmpty()) {
            eventArtistRepository.saveAll(toUpdate).toList()
        }
        if (toInsert.isNotEmpty()) {
            eventArtistRepository.saveAll(toInsert).toList()
        }
    }

    /** A detail-less event whose listing names no act: its stored lineup stands (#2421). */
    private fun keepsStoredLineup(
        sourceId: String,
        artistsBySourceId: Map<String, List<ScrapedArtist>>,
        detailless: Set<String>
    ): Boolean = sourceId in detailless && artistsBySourceId[sourceId].isNullOrEmpty()

    /** This association on [current]'s row; an untimed act keeps the stored set times when [keepsSetTimes]. */
    private fun EventArtistEntity.onRowOf(
        current: EventArtistEntity,
        keepsSetTimes: Boolean
    ): EventArtistEntity =
        if (keepsSetTimes && setStart == null && setEnd == null) {
            copy(id = current.id, setStart = current.setStart, setEnd = current.setEnd)
        } else {
            copy(id = current.id)
        }

    // -- Promoter resolution --

    /**
     * Batch-fetches known promoters by slug and auto-creates the rest.
     *
     * @return promoter slug to persisted [PromoterEntity].
     */
    private suspend fun resolveAllPromoters(scrapedEvents: List<ScrapedEvent>): Map<String, PromoterEntity> {
        // Drop bare generic labels ("Event.") first, then canonicalize so "LOFT" and "Loft Concerts
        // GmbH" resolve to one entity (isNonPromoterName / canonicalPromoterName).
        val canonicalNames =
            scrapedEvents
                .flatMap { it.promoters }
                .filterNot { isNonPromoterName(it) }
                .map { canonicalPromoterName(it) }
        val allPromoterSlugs = canonicalNames.map { SlugGenerator.slugify(it) }.toSet()
        if (allPromoterSlugs.isEmpty()) return emptyMap()

        val promoterCache =
            promoterRepository
                .findBySlugIn(allPromoterSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the promoters not already in the database
        canonicalNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { resolveOrCreatePromoter(it, promoterCache) }

        fillPromoterWebsites(scrapedEvents, promoterCache)
        return promoterCache
    }

    /**
     * Writes the website a venue links a promoter credit to onto a promoter row that has none.
     * Fill-if-empty, never replace: the venue's link is as often the promoter's ticket shop, and a
     * reviewed `website_url` (docs/promoters/REVIEWED.tsv) must not lose to it (#1319). A link back
     * onto the venue's own host is the event page (#1362).
     */
    private suspend fun fillPromoterWebsites(
        scrapedEvents: List<ScrapedEvent>,
        promoterCache: MutableMap<String, PromoterEntity>
    ) {
        val websitesBySlug =
            scrapedEvents
                .flatMap { event -> event.promoterWebsites.entries.map { (raw, url) -> Triple(raw, url, event.sourceUrl) } }
                .filterNot { (raw, url, sourceUrl) -> isNonPromoterName(raw) || url.hostOrNull() == sourceUrl.hostOrNull() }
                .associate { (raw, url, _) -> SlugGenerator.slugify(canonicalPromoterName(raw)) to url }
        websitesBySlug
            .mapNotNull { (slug, url) -> promoterCache[slug]?.takeIf { it.websiteUrl == null }?.let { it to url } }
            .forEach { (promoter, url) ->
                promoterCache[promoter.slug] = promoterRepository.save(promoter.copy(websiteUrl = url))
                logger.info { "Filled website of promoter '${promoter.slug}' from the venue's credit: $url" }
            }
    }

    /**
     * Resolves a promoter by its canonicalized [name] from [promoterCache], or auto-creates one. See
     * [resolveOrCreate].
     */
    private suspend fun resolveOrCreatePromoter(
        name: String,
        promoterCache: MutableMap<String, PromoterEntity>
    ): PromoterEntity =
        resolveOrCreate(
            name = name,
            cache = promoterCache,
            insertIfAbsent = { slug -> promoterRepository.insertIfAbsent(name, slug) },
            findBySlug = { slug -> promoterRepository.findBySlug(slug) }
        )

    // -- Promoter association syncing --

    /**
     * Synchronizes promoter associations by diff: simple many-to-many links, so insert and delete only.
     */
    private suspend fun syncPromoterAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        promoterCache: Map<String, PromoterEntity>,
        detailless: Set<String>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventPromoterRepository.findByEventIdIn(it).toList() },
                EventPromoterEntity::eventId
            ) ?: return

        // The desired associations from scraped data, filtered and canonicalized exactly as
        // resolveAllPromoters populated the cache.
        val promotersBySourceId =
            scrapedEvents.associate { event ->
                event.sourceId to
                    event.promoters
                        .filterNot { isNonPromoterName(it) }
                        .map { canonicalPromoterName(it) }
            }
        val toInsert = mutableListOf<EventPromoterEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents) {
            val (eventId, existingByPromoterId, existing) =
                eventAssociationContext(saved, existingByEventId, EventPromoterEntity::promoterId)

            val desiredPromoterNames = promotersBySourceId[saved.sourceId].orEmpty()
            // The detail page credits the promoters, so a listing that names none proves nothing (#2421).
            if (saved.sourceId in detailless && desiredPromoterNames.isEmpty()) continue
            val desiredPromoterIds = mutableSetOf<Long>()

            for (promoterName in desiredPromoterNames) {
                val slug = SlugGenerator.slugify(promoterName)
                val promoterId =
                    requireNotNull(promoterCache[slug]?.id) {
                        "Promoter '$slug' must be resolved before syncing associations"
                    }

                // `add` first, and its result is the duplicate guard, as in [syncArtistAssociations]. This is the
                // one observed: a full seed failed `frannz-club` outright (#798).
                if (desiredPromoterIds.add(promoterId) && existingByPromoterId[promoterId] == null) {
                    toInsert.add(EventPromoterEntity(eventId = eventId, promoterId = promoterId))
                }
            }

            existing
                .filter { it.promoterId !in desiredPromoterIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventPromoterRepository.deleteAllById(toDeleteIds)
        }
        if (toInsert.isNotEmpty()) {
            eventPromoterRepository.saveAll(toInsert).toList()
        }
    }

    // -- Genre tag resolution --

    /**
     * Normalizes genre strings into canonical tags, batch-fetches known tags by slug and
     * auto-creates the rest.
     *
     * @return genre tag slug to persisted [GenreTagEntity].
     */
    private suspend fun resolveAllGenreTags(scrapedEvents: List<ScrapedEvent>): Map<String, GenreTagEntity> {
        val allGenreNames = scrapedEvents.flatMap { normalizeGenre(it.storedGenre()) }.distinct()
        if (allGenreNames.isEmpty()) return emptyMap()

        val allSlugs = allGenreNames.map { SlugGenerator.slugify(it) }.toSet()
        val genreTagCache =
            genreTagRepository
                .findBySlugIn(allSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        // Auto-create only the genre tags not already in the database
        allGenreNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { resolveOrCreateGenreTag(it, genreTagCache) }

        return genreTagCache
    }

    /** Resolves a genre tag by name from [genreTagCache], or auto-creates one. See [resolveOrCreate]. */
    private suspend fun resolveOrCreateGenreTag(
        name: String,
        genreTagCache: MutableMap<String, GenreTagEntity>
    ): GenreTagEntity =
        resolveOrCreate(
            name = name,
            cache = genreTagCache,
            insertIfAbsent = { slug -> genreTagRepository.insertIfAbsent(name, slug, genreFamily(slug)?.slug) },
            findBySlug = { slug -> genreTagRepository.findBySlug(slug) }
        )

    // -- Genre tag association syncing --

    /**
     * Synchronizes genre tag associations by diff: simple links, insert and delete only.
     */
    private suspend fun syncGenreTagAssociations(
        savedEvents: List<EventEntity>,
        scrapedEvents: List<ScrapedEvent>,
        genreTagCache: Map<String, GenreTagEntity>
    ) {
        val existingByEventId =
            fetchExistingAssociationsByEventId(
                savedEvents,
                { eventGenreTagRepository.findByEventIdIn(it).toList() },
                EventGenreTagEntity::eventId
            ) ?: return

        val genresBySourceId = scrapedEvents.associate { it.sourceId to normalizeGenre(it.storedGenre()) }
        val toInsert = mutableListOf<EventGenreTagEntity>()
        val toDeleteIds = mutableListOf<Long>()

        for (saved in savedEvents) {
            val (eventId, existingByGenreTagId, existing) =
                eventAssociationContext(saved, existingByEventId, EventGenreTagEntity::genreTagId)

            val desiredGenreNames = genresBySourceId[saved.sourceId].orEmpty()
            val desiredGenreTagIds = mutableSetOf<Long>()

            for (genreName in desiredGenreNames) {
                val slug = SlugGenerator.slugify(genreName)
                val genreTagId =
                    requireNotNull(genreTagCache[slug]?.id) {
                        "Genre tag '$slug' must be resolved before syncing associations"
                    }
                desiredGenreTagIds.add(genreTagId)

                if (existingByGenreTagId[genreTagId] == null) {
                    toInsert.add(EventGenreTagEntity(eventId = eventId, genreTagId = genreTagId))
                }
            }

            existing
                .filter { it.genreTagId !in desiredGenreTagIds }
                .mapNotNull { it.id }
                .let { toDeleteIds.addAll(it) }
        }

        if (toDeleteIds.isNotEmpty()) {
            eventGenreTagRepository.deleteAllById(toDeleteIds)
        }
        if (toInsert.isNotEmpty()) {
            eventGenreTagRepository.saveAll(toInsert).toList()
        }
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
                .flatMap(::splitGuest)
                .flatMap { artist -> splitBackToBack(artist) }
                .mapNotNull { artist -> refusal(stripArtistSuffix(artist.name))?.let { it to artist.name.trim() } }
        val promoters = heldBack.map { QualityFlagKind.HELD_BACK_PROMOTER_NAME to it.name.trim() }
        val genre = event.genre?.takeIf { event.genreRepeatsTitle() }?.let { QualityFlagKind.GENRE_EQUALS_TITLE to it.trim() }
        val nonGenres = nonGenreTokens(event.storedGenre()).map { QualityFlagKind.NON_GENRE_TOKEN to it }
        return artists + promoters + listOfNotNull(genre) + nonGenres
    }

    // -- Shared helpers for association syncing --

    /**
     * Pre-computed per-event context for diff syncing, shared by the artist and promoter methods;
     * supports destructuring.
     *
     * @param T the association entity type.
     */
    private data class EventAssociationContext<T>(
        val eventId: Long,
        val existingByForeignKey: Map<Long, T>,
        val existing: List<T>
    )

    /**
     * Batch-fetches existing associations for all saved events, grouped by event ID.
     *
     * @return grouped associations, or `null` if no saved events have IDs.
     */
    private suspend fun <T> fetchExistingAssociationsByEventId(
        savedEvents: List<EventEntity>,
        fetchByEventIds: suspend (List<Long>) -> List<T>,
        getEventId: (T) -> Long
    ): Map<Long, List<T>>? {
        val savedEventIds = savedEvents.mapNotNull { it.id }
        if (savedEventIds.isEmpty()) return null
        return fetchByEventIds(savedEventIds).groupBy(getEventId)
    }

    /**
     * The per-event association context: event ID, existing associations, indexed by foreign key.
     */
    private fun <T> eventAssociationContext(
        saved: EventEntity,
        existingByEventId: Map<Long, List<T>>,
        foreignKeyExtractor: (T) -> Long
    ): EventAssociationContext<T> {
        val eventId = requireNotNull(saved.id) { "Saved event must have an ID" }
        val existing = existingByEventId[eventId].orEmpty()
        return EventAssociationContext(
            eventId = eventId,
            existingByForeignKey = existing.associateBy(foreignKeyExtractor),
            existing = existing
        )
    }

    // -- Generic resolve-or-create for slug-based entities --

    /**
     * Generic resolve-or-create for slug-based entities: [cache] first, then a conflict-tolerant
     * `INSERT … ON CONFLICT DO NOTHING` via [insertIfAbsent] and [findBySlug]. Not
     * save-then-catch: these run inside the caller's import transaction, imports run concurrently
     * and race to insert the same shared slug, and in PostgreSQL a failed statement aborts the whole
     * transaction, so re-querying after a unique violation fails with "current transaction is
     * aborted". `ON CONFLICT DO NOTHING` never raises; a lost race is a `0`-row no-op and
     * [findBySlug] returns the winner's row.
     *
     * @param T the entity type.
     * @param name the human-readable name to slugify and resolve.
     * @param cache mutable slug to entity map shared across the batch.
     * @param insertIfAbsent conflict-tolerant insert; returns rows inserted.
     * @param findBySlug fetches the entity by slug, always present after [insertIfAbsent].
     * @param slug the row's slug; an artist passes [artistSlugFor], which can differ from the name's.
     */
    private suspend fun <T : Any> resolveOrCreate(
        name: String,
        cache: MutableMap<String, T>,
        insertIfAbsent: suspend (slug: String) -> Int,
        findBySlug: suspend (slug: String) -> T?,
        slug: String = SlugGenerator.slugify(name)
    ): T {
        cache[slug]?.let { return it }

        val created = insertIfAbsent(slug) == 1
        val entity =
            findBySlug(slug)
                ?: error("Entity with slug '$slug' not found after insert-if-absent")

        if (created) {
            logger.info { "Auto-created ${entity::class.simpleName} '$name' (slug=$slug)" }
        } else {
            logger.debug { "Reused existing ${entity::class.simpleName} (slug=$slug)" }
        }
        cache[slug] = entity
        return entity
    }
}

/** The host of a URL, lower-cased and without a `www.` prefix; null where the string is not a URL. */
private fun String.hostOrNull(): String? = runCatching { URI(this).host?.lowercase()?.removePrefix("www.") }.getOrNull()

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

/** Whether [a] and [b] are the same letters in any case once accents are removed, and differ with them kept. */
internal fun differsOnlyByAccents(
    a: String,
    b: String
): Boolean = !a.equals(b, ignoreCase = true) && stripAccents(a).equals(stripAccents(b), ignoreCase = true)

private fun stripAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{M}+")
