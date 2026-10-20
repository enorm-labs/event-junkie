package de.norm.events.importing

import de.norm.events.event.EventEntity
import de.norm.events.event.EventPromoterEntity
import de.norm.events.event.EventPromoterRepository
import de.norm.events.promoter.PromoterEntity
import de.norm.events.promoter.PromoterRepository
import de.norm.events.promoter.canonicalPromoterName
import de.norm.events.promoter.isNonPromoterName
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import org.springframework.stereotype.Component
import java.net.URI

private val logger = KotlinLogging.logger {}

/**
 * The promoter step of [AssociationSyncService]: resolves the credited promoters by slug,
 * auto-creating unknown ones, and diff-syncs `event_promoter`. Runs within the caller's transaction.
 */
@Component
class PromoterAssociationSync(
    private val eventPromoterRepository: EventPromoterRepository,
    private val promoterRepository: PromoterRepository
) {
    /**
     * Batch-fetches known promoters by slug and auto-creates the rest.
     *
     * @return promoter slug to persisted [PromoterEntity].
     */
    suspend fun resolveAllPromoters(scrapedEvents: List<ScrapedEvent>): Map<String, PromoterEntity> {
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

        canonicalNames
            .distinctBy { SlugGenerator.slugify(it) }
            .forEach { name ->
                resolveOrCreate(
                    name = name,
                    cache = promoterCache,
                    insertIfAbsent = { slug -> promoterRepository.insertIfAbsent(name, slug) },
                    findBySlug = { slug -> promoterRepository.findBySlug(slug) }
                )
            }

        fillPromoterWebsites(scrapedEvents, promoterCache)
        return promoterCache
    }

    /**
     * Writes the website a venue links a promoter credit to onto a promoter row that has none.
     * Fill-if-empty, never replace: the venue's link is as often the promoter's ticket shop, and a
     * reviewed `website_url` (docs/promoters/REVIEWED.tsv) must not lose to it (#1319). A link back
     * onto the venue's own host is the event page (#1362).
     *
     * A row with `reviewed_at` set is skipped even when its website is empty: a person recorded the
     * website as absent on purpose (V028), and `scripts/promoter-websites.py` clears it for a `none`
     * row, so an import must not write a venue's link back (#3027).
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
            .mapNotNull { (slug, url) -> promoterCache[slug]?.takeIf { it.websiteUrl == null && it.reviewedAt == null }?.let { it to url } }
            .forEach { (promoter, url) ->
                promoterCache[promoter.slug] = promoterRepository.save(promoter.copy(websiteUrl = url))
                logger.info { "Filled website of promoter '${promoter.slug}' from the venue's credit: $url" }
            }
    }

    /** The ids among [eventIds] that link at least one promoter. */
    suspend fun eventIdsWithPromoters(eventIds: List<Long>): Set<Long> =
        eventPromoterRepository.findByEventIdIn(eventIds).toList().mapTo(mutableSetOf()) { it.eventId }

    /** The slugs of the promoters each of [events] credits, by event id. */
    suspend fun storedSlugs(events: List<EventEntity>): Map<Long, Set<String>> =
        storedSlugs(events, { eventPromoterRepository.findByEventIdIn(it).toList() }, { it.eventId to it.promoterId }) { ids ->
            promoterRepository.findAllById(ids).toList().associate { it.id to it.slug }
        }

    /**
     * Synchronizes promoter associations by diff: simple many-to-many links, so insert and delete only.
     */
    suspend fun syncPromoterAssociations(
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

                // `add` first, and its result is the duplicate guard, as in the artist sync. This is the
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
}

/** The promoters this event credits, as the slugs [PromoterAssociationSync.syncPromoterAssociations] resolves them to. */
internal fun ScrapedEvent.promoterSlugs(): Set<String> =
    promoters.filterNot { isNonPromoterName(it) }.mapTo(mutableSetOf()) { SlugGenerator.slugify(canonicalPromoterName(it)) }

/** The host of a URL, lower-cased and without a `www.` prefix; null where the string is not a URL. */
private fun String.hostOrNull(): String? = runCatching { URI(this).host?.lowercase()?.removePrefix("www.") }.getOrNull()
