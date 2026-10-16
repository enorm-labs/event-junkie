package de.norm.events.instagram

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class InstagramDraftServiceTest {
    private val client = mockk<BusinessDiscoveryClient>()
    private val extractor = mockk<AnthropicPostExtractor> { every { model } returns "claude-haiku-4-5" }
    private val repository = mockk<InstagramDraftRepository>(relaxed = true) { coEvery { earlierJudgement(any(), any()) } returns null }
    private val images = mockk<InstagramImageFetcher> { coEvery { fetch(any()) } returns emptyList() }
    private val clock = Clock.fixed(Instant.parse("2026-10-15T08:00:00Z"), ZoneOffset.UTC)
    private val service = InstagramDraftService(client, extractor, images, repository, InstagramProperties(enabled = true), clock)

    private fun post(
        id: String,
        caption: String? = "caption $id",
        postedAt: String = "2026-10-13T16:00:00Z",
        imageUrls: List<String> = emptyList()
    ) = InstagramPost(id, caption, "https://www.instagram.com/p/$id/", OffsetDateTime.parse(postedAt), "IMAGE", imageUrls)

    private fun event(date: String) = DraftedEvent(date = LocalDate.parse(date), title = "XTRUDE", evidence = "on Saturday", confidence = 0.9)

    @Test
    fun `a new post with events becomes pending drafts, a known one is not sent again`() =
        runTest {
            coEvery { client.recentPosts("rso.berlin") } returns DiscoveryResult.Posts(listOf(post("1"), post("2")))
            coEvery { repository.knownMediaIds(listOf("1", "2")) } returns setOf("2")
            coEvery { extractor.extract(any()) } returns ExtractionResult.Events(listOf(event("2026-10-17")), tokens = 40)
            val stored = slot<List<NewDraft>>()
            coEvery { repository.insert(capture(stored)) } returns 1

            val result = service.draftFrom(RSO, maxCalls = 10)

            result shouldBe VenueDraftResult.Read(posts = 2, newPosts = 1, drafts = 1, notEvents = 0, rejected = 0, failed = 0, calls = 1, tokens = 40)
            stored.captured.single().status shouldBe DraftStatus.PENDING
            stored.captured
                .single()
                .post.mediaId shouldBe "1"
        }

    @Test
    fun `a post with no caption, a past event and a refused answer are stored as no event`() =
        runTest {
            coEvery { client.recentPosts(any()) } returns DiscoveryResult.Posts(listOf(post("a", caption = null), post("b"), post("c")))
            coEvery { repository.knownMediaIds(any()) } returns emptySet()
            coEvery { extractor.extract(match { it.caption == "caption b" }) } returns ExtractionResult.Events(listOf(event("2026-10-14")), 40)
            coEvery { extractor.extract(match { it.caption == "caption c" }) } returns ExtractionResult.Rejected(30)

            val result = service.draftFrom(RSO, maxCalls = 10) as VenueDraftResult.Read

            result.notEvents shouldBe 2
            result.rejected shouldBe 1
            result.calls shouldBe 2
            coVerify(exactly = 3) { repository.insert(match { it.single().status == DraftStatus.NOT_AN_EVENT }) }
        }

    @Test
    fun `a failed call stores nothing, the cap stops the calls, and old posts are not read`() =
        runTest {
            val old = post("old", postedAt = "2026-08-01T10:00:00Z")
            coEvery { client.recentPosts(any()) } returns DiscoveryResult.Posts(listOf(post("1"), post("2"), old))
            coEvery { repository.knownMediaIds(listOf("1", "2")) } returns emptySet()
            coEvery { extractor.extract(any()) } returns ExtractionResult.Failed

            val result = service.draftFrom(RSO, maxCalls = 1) as VenueDraftResult.Read

            result.calls shouldBe 1
            result.failed shouldBe 1
            coVerify(exactly = 0) { repository.insert(any()) }
        }

    @Test
    fun `a post with no caption goes to the model with its images, and waits when none downloaded`() =
        runTest {
            val flyer = PostImage(byteArrayOf(1, 2, 3), MediaType.IMAGE_JPEG)
            coEvery { client.recentPosts(any()) } returns
                DiscoveryResult.Posts(listOf(post("reel", caption = null, imageUrls = listOf(COVER)), post("gone", caption = null, imageUrls = listOf(GONE))))
            coEvery { repository.knownMediaIds(any()) } returns emptySet()
            coEvery { images.fetch(listOf(COVER)) } returns listOf(flyer)
            val sent = slot<PostInput>()
            coEvery { extractor.extract(capture(sent)) } returns ExtractionResult.Events(listOf(event("2026-10-17")), tokens = 1600)
            coEvery { repository.insert(any()) } returns 1

            val result = service.draftFrom(RSO, maxCalls = 10) as VenueDraftResult.Read

            sent.captured.caption shouldBe ""
            sent.captured.images shouldBe listOf(flyer)
            result.calls shouldBe 1
            result.drafts shouldBe 1
            result.failed shouldBe 1
            coVerify(exactly = 1) { repository.insert(any()) }
        }

    @Test
    fun `a night approved before gets an update proposal, a night rejected before stays rejected`() =
        runTest {
            coEvery { client.recentPosts(any()) } returns DiscoveryResult.Posts(listOf(post("1")))
            coEvery { repository.knownMediaIds(any()) } returns emptySet()
            coEvery { extractor.extract(any()) } returns ExtractionResult.Events(listOf(event("2026-10-16"), event("2026-10-17")), tokens = 40)
            coEvery { repository.earlierJudgement(7, LocalDate.parse("2026-10-16")) } returns EarlierJudgement.Approved(8426)
            coEvery { repository.earlierJudgement(7, LocalDate.parse("2026-10-17")) } returns EarlierJudgement.Rejected
            val stored = mutableListOf<List<NewDraft>>()
            coEvery { repository.insert(capture(stored)) } answers { firstArg<List<NewDraft>>().size.toLong() }

            val result = service.draftFrom(RSO, maxCalls = 10) as VenueDraftResult.Read

            stored.flatten().map { Triple(it.event?.date.toString(), it.status, it.eventId) } shouldBe
                listOf(Triple("2026-10-16", DraftStatus.PENDING, 8426L), Triple("2026-10-17", DraftStatus.REJECTED, null))
            result.drafts shouldBe 1
            result.ofRejectedNights shouldBe 1
        }

    @Test
    fun `a dropped event and a refused answer are logged with the venue and the post`() =
        runTest {
            coEvery { client.recentPosts(any()) } returns DiscoveryResult.Posts(listOf(post("b"), post("c")))
            coEvery { repository.knownMediaIds(any()) } returns emptySet()
            coEvery { extractor.extract(match { it.caption == "caption b" }) } returns
                ExtractionResult.Events(emptyList(), 40, listOf(DroppedEvent("2026-10-31", DraftCheck.EVIDENCE_NOT_QUOTED)))
            coEvery { extractor.extract(match { it.caption == "caption c" }) } returns ExtractionResult.Rejected(30)

            val warnings = logsOf { service.draftFrom(RSO, maxCalls = 10) }.filter { it.level == Level.WARN }.map { it.formattedMessage }

            warnings shouldBe
                listOf(
                    "Instagram venue 'rso-berlin': a drafted event of https://www.instagram.com/p/b/ for '2026-10-31' was dropped: " +
                        "the evidence is not in the caption or the image text",
                    "Instagram venue 'rso-berlin': the answer for https://www.instagram.com/p/c/ was refused"
                )
        }

    private suspend fun logsOf(block: suspend () -> Unit): List<ILoggingEvent> {
        val log = LoggerFactory.getLogger(InstagramDraftService::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        log.addAppender(appender)
        try {
            block()
        } finally {
            log.detachAppender(appender)
            appender.stop()
        }
        return appender.list.toList()
    }

    @Test
    fun `no handle and an unreadable account read nothing`() =
        runTest {
            service.draftFrom(RSO.copy(instagramUrl = "https://example.com"), maxCalls = 10) shouldBe VenueDraftResult.NoHandle

            coEvery { client.recentPosts(any()) } returns DiscoveryResult.AccountUnreadable(110)
            service.draftFrom(RSO, maxCalls = 10) shouldBe VenueDraftResult.NotRead(DiscoveryResult.AccountUnreadable(110))
        }

    private companion object {
        const val COVER = "https://scontent.cdninstagram.com/v/cover.jpg"
        const val GONE = "https://scontent.cdninstagram.com/v/expired.jpg"
        val RSO = InstagramVenue(id = 7, slug = "rso-berlin", name = "RSO", instagramUrl = "https://www.instagram.com/rso.berlin/")
    }
}
