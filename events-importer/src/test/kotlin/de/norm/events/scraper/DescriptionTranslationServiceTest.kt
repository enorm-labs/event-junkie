package de.norm.events.scraper

import de.norm.events.artist.ArtistEntity
import de.norm.events.artist.ArtistRepository
import de.norm.events.event.DescriptionLanguage
import de.norm.events.event.EventArtistEntity
import de.norm.events.event.EventArtistRepository
import de.norm.events.event.EventEntity
import de.norm.events.event.EventRepository
import de.norm.events.licence.SourceLicence
import de.norm.events.licence.SourceLicences
import de.norm.events.translation.TranslationEngine
import de.norm.events.translation.TranslationProperties
import de.norm.events.translation.TranslationRequest
import de.norm.events.venue.VenueEntity
import de.norm.events.venue.VenueRepository
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.slf4j.MDC
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicReference

/**
 * The gate, and what a translation is made from.
 *
 * **The gate is the part worth pinning.** Only `PERMITTED` allows a translation, which is the
 * opposite of the display rule for the same field, and a change that "tidied" the two into one
 * would create a § 23 UrhG act for 83 sources without anybody deciding (ADR-026).
 */
class DescriptionTranslationServiceTest {
    private val eventRepository = mockk<EventRepository>(relaxed = true)
    private val eventArtistRepository = mockk<EventArtistRepository>()
    private val artistRepository = mockk<ArtistRepository>()
    private val eventSourceRepository = mockk<EventSourceRepository>()
    private val venueRepository = mockk<VenueRepository>()
    private val engine = mockk<TranslationEngine>()

    private val service =
        DescriptionTranslationService(
            eventRepository = eventRepository,
            eventArtistRepository = eventArtistRepository,
            artistRepository = artistRepository,
            eventSourceRepository = eventSourceRepository,
            venueRepository = venueRepository,
            engine = engine,
            properties = TranslationProperties(),
            metrics = ImporterMetrics(SimpleMeterRegistry())
        )

    @Test
    @DisplayName("an UNCLEAR source is not translated")
    fun `declines an unclear source`(): Unit =
        runBlocking {
            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.UNCLEAR)) shouldBe 0

            coVerify(exactly = 0) { engine.translate(any()) }
        }

    // Nobody asked this source yet. Silence is not consent for an adaptation.
    @Test
    @DisplayName("an unreviewed source is not translated")
    fun `declines an unreviewed source`(): Unit =
        runBlocking {
            service.translateFor(source(), VENUE_NAME, licences(null)) shouldBe 0

            coVerify(exactly = 0) { engine.translate(any()) }
        }

    @Test
    @DisplayName("a PERMITTED source is translated into the other language")
    fun `translates a permitted source`(): Unit =
        runBlocking {
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            coEvery { engine.translate(any()) } returns "An evening with a view."
            every { engine.id } returns "test:engine"

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED)) shouldBe 1

            val saved = slot<EventEntity>()
            coVerify { eventRepository.save(capture(saved)) }
            saved.captured.descriptionAlt shouldBe "An evening with a view."
            saved.captured.descriptionAltLanguage shouldBe "en"
            saved.captured.descriptionAltOrigin shouldBe "MACHINE"
            saved.captured.descriptionAltEngine shouldBe "test:engine"
            saved.captured.descriptionAltSourceHash shouldBe DescriptionLanguage.hash(GERMAN_TEXT)
        }

    // The engine's own lines say why a description was refused. Without the id they say it about
    // one of fifty, and the engine is not told which event it is translating.
    @Test
    @DisplayName("the engine is called inside the event's log context")
    fun `names the event for the engine's lines`(): Unit =
        runBlocking {
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            val seen = AtomicReference<String?>()
            coEvery { engine.translate(any()) } answers {
                seen.set(MDC.get(LogFields.EVENT_ID))
                null
            }

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED))

            seen.get() shouldBe EVENT_ID.toString()
            MDC.get(LogFields.EVENT_ID) shouldBe null
        }

    // The venue and the acts on the bill are the words a translation is most likely to damage.
    @Test
    @DisplayName("the venue and the line-up are handed to the engine as protected names")
    fun `protects the names on the bill`(): Unit =
        runBlocking {
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            coEvery { engine.translate(any()) } returns "An evening with a view."
            every { engine.id } returns "test:engine"

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED))

            val request = slot<TranslationRequest>()
            coVerify { engine.translate(capture(request)) }
            request.captured.protectedTerms shouldContainAll listOf(VENUE_NAME, "Elsa Shelelé")
            request.captured.from shouldBe DescriptionLanguage.GERMAN
            request.captured.to shouldBe DescriptionLanguage.ENGLISH
        }

    // An engine that declines is an ordinary outcome. The row keeps what it had.
    @Test
    @DisplayName("an engine that returns nothing writes nothing")
    fun `writes nothing when the engine declines`(): Unit =
        runBlocking {
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            coEvery { engine.translate(any()) } returns null
            every { engine.id } returns "none"

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED)) shouldBe 0

            coVerify(exactly = 0) { eventRepository.save(any()) }
        }

    // The hash is what makes a re-import cheap: an unchanged description needs no second call.
    @Test
    @DisplayName("a translation still matching its source text is left alone")
    fun `skips a current translation`(): Unit =
        runBlocking {
            givenOneCandidate(
                event(description = GERMAN_TEXT, language = "de").copy(
                    descriptionAlt = "An evening with a view.",
                    descriptionAltLanguage = "en",
                    descriptionAltOrigin = "MACHINE",
                    descriptionAltSourceHash = DescriptionLanguage.hash(GERMAN_TEXT)
                )
            )

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED)) shouldBe 0

            coVerify(exactly = 0) { engine.translate(any()) }
        }

    @Test
    @DisplayName("a re-scraped description invalidates the translation made from the old one")
    fun `re-translates a changed description`(): Unit =
        runBlocking {
            givenOneCandidate(
                event(description = GERMAN_TEXT, language = "de").copy(
                    descriptionAlt = "An evening with a view.",
                    descriptionAltLanguage = "en",
                    descriptionAltOrigin = "MACHINE",
                    descriptionAltSourceHash = DescriptionLanguage.hash("something the venue has since replaced")
                )
            )
            coEvery { engine.translate(any()) } returns "A different evening."
            every { engine.id } returns "test:engine"

            service.translateFor(source(), VENUE_NAME, licences(SourceLicence.PERMITTED)) shouldBe 1
        }

    // --- The admin trigger. Same gate, different reporting: an operator wants the three outcomes
    // --- a bare count cannot tell apart.

    @Test
    @DisplayName("the trigger reports a source that grants nothing, rather than translating anyway")
    fun `reports an ungranted source on demand`(): Unit =
        runBlocking {
            givenSource(SourceLicence.UNCLEAR)
            every { engine.id } returns "test:engine"

            val result = service.translateOnDemand("klunkerkranich", eventSlug = null)

            result.permitted shouldBe false
            result.candidates shouldBe 0
            result.translated shouldBe 0
            coVerify(exactly = 0) { engine.translate(any()) }
        }

    @Test
    @DisplayName("the trigger translates a granted source and counts what it did")
    fun `translates on demand`(): Unit =
        runBlocking {
            givenSource(SourceLicence.PERMITTED)
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            coEvery { engine.translate(any()) } returns "An evening with a view."
            every { engine.id } returns "test:engine"

            val result = service.translateOnDemand("klunkerkranich", eventSlug = null)

            result.permitted shouldBe true
            result.candidates shouldBe 1
            result.translated shouldBe 1
            result.engine shouldBe "test:engine"
        }

    @Test
    @DisplayName("naming an event translates only that one")
    fun `filters to one event`(): Unit =
        runBlocking {
            givenSource(SourceLicence.PERMITTED)
            givenOneCandidate(event(description = GERMAN_TEXT, language = "de"))
            every { engine.id } returns "test:engine"

            val result = service.translateOnDemand("klunkerkranich", eventSlug = "a-different-event")

            result.candidates shouldBe 0
            coVerify(exactly = 0) { engine.translate(any()) }
        }

    @Test
    @DisplayName("an unknown slug is a not-found, not an empty run")
    fun `rejects an unknown source`(): Unit =
        runBlocking {
            coEvery { eventSourceRepository.findBySlug("nope") } returns null

            shouldThrow<EventSourceNotFoundException> { service.translateOnDemand("nope", eventSlug = null) }
        }

    private fun givenSource(translation: SourceLicence?) {
        coEvery { eventSourceRepository.findBySlug("klunkerkranich") } returns
            source().copy(descriptionLicence = "UNCLEAR", imageLicence = "UNCLEAR", translationLicence = translation?.name)
        coEvery { venueRepository.findById(1L) } returns VenueEntity(id = 1L, name = VENUE_NAME, slug = "klunkerkranich")
    }

    private fun givenOneCandidate(event: EventEntity) {
        every { eventRepository.findTranslationCandidates(SOURCE_ID) } returns flowOf(event)
        every { eventArtistRepository.findByEventId(EVENT_ID) } returns
            flowOf(EventArtistEntity(id = 1L, eventId = EVENT_ID, artistId = ARTIST_ID))
        coEvery { artistRepository.findById(ARTIST_ID) } returns
            ArtistEntity(id = ARTIST_ID, name = "Elsa Shelelé", slug = "elsa-shelele")
        // A relaxed mock answers `save` with a bare Object, which the generic return type cannot hold.
        coEvery { eventRepository.save(any<EventEntity>()) } answers { firstArg() }
    }

    private fun licences(translation: SourceLicence?) =
        SourceLicences(description = SourceLicence.UNCLEAR, image = SourceLicence.UNCLEAR, translation = translation)

    private fun source() =
        EventSourceEntity(
            id = SOURCE_ID,
            venueId = 1L,
            name = "Klunkerkranich",
            slug = "klunkerkranich",
            url = "https://klunkerkranich.org/events",
            sourceType = "KLUNKERKRANICH"
        )

    private fun event(
        description: String,
        language: String
    ) = EventEntity(
        id = EVENT_ID,
        venueId = 1L,
        eventSourceId = SOURCE_ID,
        title = "Monday Roast",
        slug = "2026-09-07-klunkerkranich-monday-roast",
        eventDate = LocalDate.of(2026, 9, 7),
        sourceId = "klunkerkranich:1",
        description = description,
        descriptionLanguage = language
    )

    private companion object {
        const val SOURCE_ID = 7L
        const val EVENT_ID = 42L
        const val ARTIST_ID = 3L
        const val VENUE_NAME = "Klunkerkranich"
        const val GERMAN_TEXT = "Ein Abend mit Aussicht über die Dächer von Neukölln."
    }
}
