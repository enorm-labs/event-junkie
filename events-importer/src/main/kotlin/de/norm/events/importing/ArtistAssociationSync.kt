package de.norm.events.importing

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.artist.ArtistSlugAliasStore
import de.norm.events.artist.MusicBrainzMatch
import de.norm.events.artist.artistSlugFor
import de.norm.events.artist.canonicalArtistName
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventType
import de.norm.events.musicbrainz.MusicBrainzMatcher
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isSlugless
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.splitBracketedGuest
import de.norm.events.scraper.splitOnEmoji
import de.norm.events.scraper.stripArtistSuffix
import de.norm.events.scraper.stripTitleStatusMarker
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.text.Normalizer

private val logger = KotlinLogging.logger {}

/**
 * The lineup step of [AssociationSyncService]: decides which acts each event bills, resolves them
 * by slug, auto-creating unknown ones, and diff-syncs `event_artist`. Runs within the caller's transaction.
 */
@Component
class ArtistAssociationSync(
    private val eventArtistRepository: EventArtistRepository,
    private val artistRepository: ArtistRepository,
    private val artistSlugAliasStore: ArtistSlugAliasStore
) {
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
     * published line-up stays. A status marker the title carried comes off first (`Postponed: Nina`,
     * `Neunundneunzig -Abgesagt-`, [stripTitleStatusMarker], #2985), then a guest in a bracket
     * ([splitBracketedGuest]), then a `b2b` slot into its DJs ([splitBackToBack]), for every source.
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
     * An emoji run between two names separates two acts ([splitOnEmoji], #3038), so it is split before the `b2b` step.
     *
     * @return the billed artists by `sourceId`, and the held-back names by `sourceId`.
     */
    suspend fun billedArtists(scrapedEvents: List<ScrapedEvent>): Pair<Map<String, List<ScrapedArtist>>, Map<String, List<ScrapedArtist>>> {
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
                        .map { it.copy(name = stripTitleStatusMarker(it.name)) }
                        .flatMap { artist -> splitGuest(artist) }
                        .flatMap { artist -> splitOnEmoji(artist) }
                        .flatMap { artist -> splitBackToBack(artist) }
                        .map { it.copy(name = stripArtistSuffix(it.name, fromTitle = it.titleDerived)) }
                        .filterNot { artistRefusal(it.name) != null || (it.titleDerived && festival) }
            }
        val promoterSlugsById = scrapedEvents.associate { event -> event.sourceId to event.promoters.map(::artistSlugFor).toSet() }
        val promoterNamed =
            stripped.flatMap { (sourceId, artists) ->
                artists.filter { it.titleDerived && artistSlugFor(it.name) in promoterSlugsById.getValue(sourceId) }
            }
        val verified = exactSlugs(promoterNamed.map { artistSlugFor(it.name) }.toSet())
        val heldBack =
            stripped
                .mapValues { (sourceId, artists) ->
                    artists.filter { it.titleDerived && artistSlugFor(it.name) in promoterSlugsById.getValue(sourceId) && artistSlugFor(it.name) !in verified }
                }.filterValues { it.isNotEmpty() }
        val billed = stripped.mapValues { (sourceId, artists) -> artists - heldBack[sourceId].orEmpty().toSet() }
        heldBack.values.flatten().distinctBy { artistSlugFor(it.name) }.forEach {
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
        val slugsToRead = headByName.flatMap { (name, head) -> listOf(artistSlugFor(name), artistSlugFor(head)) }.toSet()
        val exactBySlug =
            artistRepository
                .findBySlugIn(slugsToRead)
                .toList()
                .filter { it.musicbrainzMatch == MusicBrainzMatch.EXACT.name }
                .associateBy { it.slug }
        return headByName
            .mapNotNull { (name, head) ->
                val headRow = exactBySlug[artistSlugFor(head)]
                if (headRow != null && exactBySlug[artistSlugFor(name)] == null) name to headRow.name else null
            }.toMap()
    }

    // -- Resolution --

    /**
     * Batch-fetches known artists by slug and auto-creates the rest.
     *
     * @return artist slug to persisted [ArtistEntity] for every artist the scraped events reference.
     */
    suspend fun resolveAllArtists(scrapedArtists: List<ScrapedArtist>): Map<String, ArtistEntity> {
        // Canonicalize before slugging, as the promoter path does: a curated NAME_CORRECTIONS entry can
        // change the slug ("OXO86" resolves to "Oxo 86", `oxo-86`), and slugging the raw name would look
        // up a different row than [resolveOrCreate] creates.
        val allArtistSlugs = scrapedArtists.map { artistSlugFor(it.name) }.toSet()
        val artistCache =
            artistRepository
                .findBySlugIn(allArtistSlugs)
                .toList()
                .associateBy { it.slug }
                .toMutableMap()

        warnAboutAccentOnlyMatches(scrapedArtists, artistCache)
        artistCache.putAll(artistsByFormerSlug(allArtistSlugs - artistCache.keys))

        // Auto-create only the artists not in the database, with the display name canonicalized first so
        // an act is not frozen SHOUTING by whichever venue imported it first.
        scrapedArtists
            .distinctBy { artistSlugFor(it.name) }
            .map { canonicalArtistName(it.name) }
            .forEach { name ->
                resolveOrCreate(
                    name = name,
                    cache = artistCache,
                    insertIfAbsent = { slug -> artistRepository.insertIfAbsent(name, slug) },
                    findBySlug = { slug -> artistRepository.findBySlug(slug) },
                    slug = artistSlugFor(name)
                )
            }

        return artistCache
    }

    /**
     * The renamed artists among [slugs], by the former slug the scraped name still takes (#3289). A
     * rename in the admin API changes the slug. Without this the import would create the old name again.
     */
    private suspend fun artistsByFormerSlug(slugs: Set<String>): Map<String, ArtistEntity> {
        val idsBySlug = artistSlugAliasStore.artistIdsBySlug(slugs)
        if (idsBySlug.isEmpty()) return emptyMap()
        val byId = artistRepository.findAllById(idsBySlug.values.toSet()).toList().associateBy { it.id }
        return idsBySlug
            .mapNotNull { (slug, id) -> byId[id]?.let { slug to it } }
            .toMap()
            .onEach { (slug, artist) -> logger.debug { "Billed slug '$slug' resolved to renamed artist '${artist.name}' (${artist.slug})" } }
    }

    /** Each set of [slugsBySourceId] with a renamed artist's former slug replaced by its current one. */
    suspend fun renamedArtistSlugs(slugsBySourceId: Map<String, Set<String>>): Map<String, Set<String>> {
        val renamed = artistsByFormerSlug(slugsBySourceId.values.flatten().toSet()).mapValues { it.value.slug }
        return if (renamed.isEmpty()) slugsBySourceId else slugsBySourceId.mapValues { (_, slugs) -> slugs.mapTo(mutableSetOf()) { renamed[it] ?: it } }
    }

    // -- Stored rows --

    /** The ids among [eventIds] that link at least one artist. */
    suspend fun eventIdsWithLineup(eventIds: List<Long>): Set<Long> =
        eventArtistRepository.findByEventIdIn(eventIds).toList().mapTo(mutableSetOf()) { it.eventId }

    /** The slugs of the artists each of [events] bills, by event id. */
    suspend fun storedSlugs(events: List<EventEntity>): Map<Long, Set<String>> =
        storedSlugs(events, { eventArtistRepository.findByEventIdIn(it).toList() }, { it.eventId to it.artistId }) { ids ->
            artistRepository.findAllById(ids).toList().associate { it.id to it.slug }
        }

    // -- Association syncing --

    /**
     * Synchronizes artist associations by diff, matched on `(eventId, artistId)`: inserts new,
     * updates changed role, billing order, floor or set times, deletes removed, skips the rest. Deleting and
     * re-creating on every import wastes auto-increment IDs.
     *
     * An event in [detailless] keeps its stored lineup when the listing names no act, and an act's
     * stored set times when the listing gives none: the detail page is their source (#2421).
     */
    suspend fun syncArtistAssociations(
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
}

/** Why the gate keeps [name] off a lineup, or null when the name may stay. */
internal fun artistRefusal(name: String): QualityFlagKind? =
    when {
        isSlugless(name) -> QualityFlagKind.SLUGLESS_ARTIST
        isNonArtistName(name) -> QualityFlagKind.NON_ARTIST_NAME
        else -> null
    }

/** [splitBracketedGuest] on one billing; a headliner's guest is support, as a `feat.` title bills it (#305). */
internal fun splitGuest(artist: ScrapedArtist): List<ScrapedArtist> =
    splitBracketedGuest(artist.name).mapIndexed { index, name ->
        val role = if (index > 0 && artist.role == "HEADLINER") "SUPPORT" else artist.role
        artist.copy(name = name, role = role)
    }

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

/** Whether [a] and [b] are the same letters in any case once accents are removed, and differ with them kept. */
private fun differsOnlyByAccents(
    a: String,
    b: String
): Boolean = !a.equals(b, ignoreCase = true) && stripAccents(a).equals(stripAccents(b), ignoreCase = true)

private fun stripAccents(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFD).replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{M}+")
