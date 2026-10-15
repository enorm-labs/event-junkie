package de.norm.events.instagram

import de.norm.events.BaseControllerTest
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.r2dbc.core.await
import java.time.LocalDate
import java.time.OffsetDateTime

/** The V163 tables against a real PostgreSQL: the constraints that hold ADR-048's rules, and the queries the pass runs. */
class InstagramDraftRepositoryTest : BaseControllerTest() {
    @Autowired
    private lateinit var repository: InstagramDraftRepository

    private suspend fun venue(
        slug: String,
        instagramUrl: String?
    ): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug, instagram_url) VALUES (:name, :slug, :url) RETURNING id")
            .bind("name", slug.uppercase())
            .bind("slug", slug)
            .let { if (instagramUrl == null) it.bindNull("url", String::class.java) else it.bind("url", instagramUrl) }
            .map { row, _ -> (row.get("id", Number::class.java) ?: error("no id")).toLong() }
            .one()
            .awaitSingle()

    private val post =
        InstagramPost("m1", "We host XTRUDE on Saturday", "https://www.instagram.com/p/m1/", OffsetDateTime.parse("2026-10-13T16:00:12Z"), "IMAGE")

    private fun event(date: String) =
        DraftedEvent(date = LocalDate.parse(date), title = "XTRUDE", acts = listOf("space.trax"), evidence = "on Saturday", confidence = 0.9)

    @Test
    fun `lists only the venues switched on`() =
        runTest {
            val rso = venue("rso-berlin", "https://www.instagram.com/rso.berlin/")
            venue("prisma", "https://www.instagram.com/prisma.berlin/")
            databaseClient.sql("INSERT INTO events.instagram_venue (venue_id) VALUES ($rso)").await()

            repository.findVenues() shouldBe listOf(InstagramVenue(rso, "rso-berlin", "RSO-BERLIN", "https://www.instagram.com/rso.berlin/"))
        }

    @Test
    fun `stores drafts once per post and date, and knows the post afterwards`() =
        runTest {
            val rso = venue("rso-berlin", null)
            val drafts = listOf(NewDraft(rso, post, DraftStatus.PENDING, event("2026-10-17"), "claude-haiku-4-5"))

            repository.insert(drafts) shouldBe 1
            repository.insert(drafts) shouldBe 0
            repository.insert(listOf(NewDraft(rso, post.copy(mediaId = "m2"), DraftStatus.NOT_AN_EVENT))) shouldBe 1

            repository.knownMediaIds(listOf("m1", "m2", "m3")) shouldBe setOf("m1", "m2")
            val stored =
                databaseClient
                    .sql("SELECT caption, acts, status FROM events.instagram_draft ORDER BY media_id")
                    .map {
                        row,
                        _
                        ->
                        Triple(
                            row.get("caption", String::class.java),
                            row.get("acts", Array<String>::class.java)?.toList(),
                            row.get("status", String::class.java)
                        )
                    }.all()
                    .collectList()
                    .awaitSingle()
            stored shouldBe
                listOf(
                    Triple("We host XTRUDE on Saturday", listOf("space.trax"), "PENDING"),
                    Triple(null, emptyList(), "NOT_AN_EVENT")
                )
        }

    @Test
    fun `the database refuses a caption on a judged draft and a draft without a date`() =
        runTest {
            val rso = venue("rso-berlin", null)
            repository.insert(listOf(NewDraft(rso, post, DraftStatus.PENDING, event("2026-10-17"))))

            shouldThrow<DataIntegrityViolationException> {
                databaseClient.sql("UPDATE events.instagram_draft SET status = 'APPROVED'").await()
            }
            shouldThrow<DataIntegrityViolationException> {
                databaseClient
                    .sql(
                        "INSERT INTO events.instagram_draft (venue_id, media_id, permalink, posted_at, status) " +
                            "VALUES ($rso, 'm9', 'https://www.instagram.com/p/m9/', now(), 'PENDING')"
                    ).await()
            }
        }
}
