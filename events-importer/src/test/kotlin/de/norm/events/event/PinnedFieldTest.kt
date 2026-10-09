package de.norm.events.event

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import kotlin.reflect.full.primaryConstructor

/** Unit tests for [PinnedField.keepPinned], the merge that keeps a hand edit through an import (ADR-042). */
class PinnedFieldTest {
    private val stored =
        EventEntity(
            id = 7,
            venueId = 1,
            title = "Fixed title",
            description = "Fixed blurb",
            descriptionLanguage = "en",
            descriptionAlt = "Fester Text",
            descriptionAltLanguage = "de",
            descriptionAltOrigin = "MACHINE",
            slug = "2026-10-10-venue-fixed-title",
            eventDate = LocalDate.of(2026, 10, 10),
            endDate = LocalDate.of(2026, 10, 11),
            endTime = LocalTime.of(6, 0),
            startTime = LocalTime.of(22, 0),
            sourceId = "test:1",
            pricePresale = BigDecimal("12.00")
        )

    /** The row the source would write: another title, blurb, date and price. */
    private val fromSource =
        stored.copy(
            title = "Source title",
            description = "Source blurb",
            descriptionLanguage = null,
            descriptionAlt = null,
            descriptionAltLanguage = null,
            descriptionAltOrigin = null,
            slug = "2026-10-12-venue-source-title",
            eventDate = LocalDate.of(2026, 10, 12),
            endDate = null,
            endTime = null,
            startTime = LocalTime.of(23, 0),
            pricePresale = BigDecimal("15.00")
        )

    @Test
    fun `a row without pins is the source's row`() {
        PinnedField.keepPinned(fromSource, stored) shouldBeSameInstanceAs fromSource
        PinnedField.keepPinned(fromSource, null) shouldBeSameInstanceAs fromSource
    }

    @Test
    fun `a pinned column keeps the stored value, and every other column takes the source's`() {
        val merged = PinnedField.keepPinned(fromSource, stored.copy(pinnedFields = listOf("pricePresale")))

        merged.pricePresale shouldBe BigDecimal("12.00")
        merged.startTime shouldBe LocalTime.of(23, 0)
        merged.title shouldBe "Source title"
        merged.slug shouldBe fromSource.slug
        merged.pinnedFields shouldBe listOf("pricePresale")
    }

    @Test
    fun `a pinned title or date keeps the slug, and a pinned date keeps its end`() {
        val merged = PinnedField.keepPinned(fromSource, stored.copy(pinnedFields = listOf("eventDate")))

        merged.eventDate shouldBe stored.eventDate
        merged.endDate shouldBe stored.endDate
        merged.endTime shouldBe stored.endTime
        merged.slug shouldBe stored.slug
        PinnedField.keepPinned(fromSource, stored.copy(pinnedFields = listOf("title"))).slug shouldBe stored.slug
    }

    @Test
    fun `a pinned description keeps its language and translation`() {
        val merged = PinnedField.keepPinned(fromSource, stored.copy(pinnedFields = listOf("description")))

        merged.description shouldBe "Fixed blurb"
        merged.descriptionLanguage shouldBe "en"
        merged.descriptionAlt shouldBe "Fester Text"
        merged.descriptionAltOrigin shouldBe "MACHINE"
    }

    @Test
    fun `a pin no field has is carried and changes nothing`() {
        val merged = PinnedField.keepPinned(fromSource, stored.copy(pinnedFields = listOf("retired")))

        merged shouldBe fromSource.copy(pinnedFields = listOf("retired"))
    }

    @Test
    fun `differs reads a column, and never a join table`() {
        PinnedField.TITLE.differs(fromSource, stored) shouldBe true
        PinnedField.SOLD_OUT.differs(fromSource, stored) shouldBe false
        PinnedField.LINEUP.differs(fromSource, stored) shouldBe false
    }

    @Test
    fun `every editable request property is a pinnable column`() {
        // No import writes the pick (#1262), so there is nothing to keep it from.
        val notPinnable = setOf("sourceId", "artists", "promoterIds", "featuredUntil")
        val editable =
            EventRequest::class
                .primaryConstructor!!
                .parameters
                .map { it.name!! }
                .toSet() - notPinnable

        PinnedField.entries
            .filter { it.isColumn }
            .map { it.key }
            .toSet() shouldBe editable
        PinnedField.fromKey("lineup") shouldBe PinnedField.LINEUP
        PinnedField.fromKey("sourceId") shouldBe null
    }
}
