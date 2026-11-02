package de.norm.events.genretag

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * Brings every stored `genre_tag.family` in line with [genreFamily] once per importer start.
 *
 * The map is code, so a remap ships as a release and the rows have to follow without a `--full`
 * re-seed: the tag insert only sets the family for a tag it creates, and the tags that matter
 * already exist. Running at start rather than per import keeps the import pipeline free of a
 * full-table pass, and a start is when the map can have changed.
 *
 * The tags left without a family are logged by name. That list is the to-do for the map, or for
 * `NON_GENRE_TOKENS` when the tag is not a genre at all — the two outcomes #363 chose over a
 * delete.
 */
@Service
class GenreFamilyReconciler(
    private val genreTagRepository: GenreTagRepository
) : ApplicationRunner {
    /** `runBlocking` is fine here: the runner executes on the main thread, not an event loop. */
    override fun run(args: ApplicationArguments) {
        runBlocking { reconcile() }
    }

    /**
     * Rewrites the family of every tag whose stored value differs from the map's, and returns how
     * many rows changed.
     */
    suspend fun reconcile(): Int {
        val tags = genreTagRepository.findAll().toList()
        var changed = 0
        tags.forEach { tag ->
            val expected = genreFamily(tag.slug)?.slug
            if (tag.family != expected) {
                changed += genreTagRepository.updateFamily(tag.requiredId(), expected)
            }
        }
        val orphans = tags.filter { genreFamily(it.slug) == null }.map { it.name }.sorted()
        logger.info { "Reconciled genre families: ${tags.size} tags, $changed changed, ${orphans.size} without a family" }
        if (orphans.isNotEmpty()) logger.info { "Genre tags without a family: ${orphans.joinToString(", ")}" }
        return changed
    }
}
