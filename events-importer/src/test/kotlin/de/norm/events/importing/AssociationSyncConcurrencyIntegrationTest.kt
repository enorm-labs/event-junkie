package de.norm.events.importing

import de.norm.events.BaseControllerTest
import de.norm.events.genretag.GenreTagRepository
import de.norm.events.slug.SlugGenerator
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * Integration test for the concurrent-insert handling in [AssociationSyncService]'s slug-based
 * entity resolution, against a real PostgreSQL (Testcontainers).
 *
 * Reproduces the race that previously failed imports: multiple import transactions run
 * concurrently ([EventImportService.importConcurrently]) and race to insert the same shared
 * slug (a common genre, a co-billed artist). The `INSERT … ON CONFLICT DO NOTHING` + read-back
 * strategy must let the loser's transaction survive — the old catch-unique-violation-then-reselect
 * approach aborted it with "current transaction is aborted, commands ignored until end of
 * transaction block".
 *
 * Exercised through [GenreTagRepository] (representative — artist/promoter share the identical
 * `insertIfAbsent` shape and the same generic `resolveOrCreate`).
 */
class AssociationSyncConcurrencyIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var genreTagRepository: GenreTagRepository

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

    // Block bodies (not `= runBlocking { … }`): an expression body whose last statement returns
    // non-Unit makes JUnit silently skip the test — see the project memory on runBlocking tests.
    @Test
    fun `insertIfAbsent creates once then is a no-op for the same slug`() {
        runBlocking {
            val name = "Drum & Bass"
            val slug = SlugGenerator.slugify(name)

            genreTagRepository.insertIfAbsent(name, slug, null) shouldBe 1
            genreTagRepository.insertIfAbsent(name, slug, null) shouldBe 0

            genreTagRepository.findBySlugIn(setOf(slug)).toList() shouldHaveSize 1
            genreTagRepository.findBySlug(slug).shouldNotBeNull()
        }
    }

    @Test
    fun `concurrent transactions inserting the same slug all succeed without aborting`() {
        runBlocking {
            val name = "Post-Punk"
            val slug = SlugGenerator.slugify(name)
            val workers = 16

            // Each worker mirrors resolveOrCreate inside its own transaction: a conflict-tolerant
            // insert followed by a read in the SAME transaction. The follow-up read is the statement
            // that used to blow up with "transaction is aborted" once a racing insert lost.
            val resolved =
                coroutineScope {
                    (1..workers)
                        .map {
                            async(Dispatchers.IO) {
                                transactionalOperator.executeAndAwait {
                                    genreTagRepository.insertIfAbsent(name, slug, null)
                                    genreTagRepository.findBySlug(slug)
                                }
                            }
                        }.awaitAll()
                }

            // Every worker resolved the tag (none threw / aborted), and exactly one row was created.
            resolved.forEach { it.shouldNotBeNull() }
            resolved.mapNotNull { it?.id }.toSet() shouldHaveSize 1
            genreTagRepository.findBySlugIn(setOf(slug)).toList() shouldHaveSize 1
        }
    }
}
