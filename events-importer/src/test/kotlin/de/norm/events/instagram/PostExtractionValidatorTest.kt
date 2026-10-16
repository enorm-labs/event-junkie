package de.norm.events.instagram

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** Captions from the #2592 census, and the answers a model could give for them. */
class PostExtractionValidatorTest {
    private fun parse(
        answer: String,
        caption: String = RSO_WEEK,
        postedAt: String = "2026-10-13T16:00:12Z",
        images: List<PostImage> = emptyList()
    ) = parsed(answer, caption, postedAt, images)?.events

    private fun parsed(
        answer: String,
        caption: String = RSO_WEEK,
        postedAt: String = "2026-10-13T16:00:12Z",
        images: List<PostImage> = emptyList()
    ) = PostExtractionValidator.parse(answer, PostInput("RSO", caption, OffsetDateTime.parse(postedAt), images), minConfidence = 0.7)

    @Test
    fun `each dropped event names the check it failed and the date the model gave`() {
        val answer =
            """
            {"isEvent": true, "events": [
              {"date": "16.10.", "title": "a", "evidence": "on Friday", "confidence": 0.9},
              {"date": "2026-10-16", "title": "", "evidence": "on Friday", "confidence": 0.9},
              {"date": "2026-10-16", "title": "c", "evidence": "on Friday", "confidence": 0.5},
              {"date": "2026-10-16", "title": "d", "evidence": "on", "confidence": 0.9},
              {"date": "2026-10-16", "title": "e", "evidence": "Friday at Berghain", "confidence": 0.9},
              {"date": "2026-10-09", "title": "f", "evidence": "on Friday", "confidence": 0.9},
              {"date": "2026-10-15", "title": "g", "evidence": "on the SUMME floor on Friday", "confidence": 0.9},
              {"date": "2026-10-16", "title": "h", "evidence": "on the SUMME floor on Friday", "confidence": 0.9}
            ]}
            """.trimIndent()

        val result = parsed(answer)

        result?.events?.map { it.title } shouldBe listOf("h")
        result?.dropped shouldBe
            listOf(
                DroppedEvent("16.10.", DraftCheck.DATE),
                DroppedEvent("2026-10-16", DraftCheck.TITLE),
                DroppedEvent("2026-10-16", DraftCheck.CONFIDENCE),
                DroppedEvent("2026-10-16", DraftCheck.EVIDENCE_LENGTH),
                DroppedEvent("2026-10-16", DraftCheck.EVIDENCE_NOT_QUOTED),
                DroppedEvent("2026-10-09", DraftCheck.HORIZON),
                DroppedEvent("2026-10-15", DraftCheck.WEEKDAY)
            )
    }

    @Test
    fun `weekdays counted from the post become two events`() {
        val events =
            parse(
                """
                {"isEvent": true, "events": [
                  {"date": "2026-10-16", "title": "selfcontrolbln", "evidence": "selfcontrolbln on the SUMME floor on Friday", "confidence": 0.9},
                  {"date": "2026-10-17", "title": "XTRUDE", "evidence": "on Saturday we team up with elli.acula", "confidence": 0.8}
                ]}
                """.trimIndent()
            )

        events?.map { it.date to it.title } shouldBe listOf(LocalDate.of(2026, 10, 16) to "selfcontrolbln", LocalDate.of(2026, 10, 17) to "XTRUDE")
    }

    @Test
    fun `a date on another weekday than the quote names is dropped`() {
        parse("""{"isEvent": true, "events": [{"date": "2026-10-15", "title": "x", "evidence": "on the SUMME floor on Friday", "confidence": 0.9}]}""")
            ?.shouldBeEmpty()
    }

    @Test
    fun `a quote that is not in the caption, a low confidence and a date before the post are dropped`() {
        parse(
            """
            {"isEvent": true, "events": [
              {"date": "2026-10-16", "title": "a", "evidence": "Friday at Berghain", "confidence": 0.9},
              {"date": "2026-10-16", "title": "b", "evidence": "on Friday", "confidence": 0.5},
              {"date": "2026-10-09", "title": "c", "evidence": "on Friday", "confidence": 0.9}
            ]}
            """.trimIndent()
        )?.shouldBeEmpty()
    }

    @Test
    fun `a wrong year beyond the horizon is dropped`() {
        parse(
            """{"isEvent": true, "events": [{"date": "2027-10-16", "title": "s", "evidence": "next Friday, October 16th", "confidence": 0.9}]}""",
            caption = "selfcontrolbln returns to the SUMME floor next Friday, October 16th"
        )?.shouldBeEmpty()
    }

    @Test
    fun `the time, the acts and the prices stay only when the caption names them`() {
        val event =
            parse(
                """
                {"isEvent": true, "events": [{"date": "2026-10-04", "title": "SUNSET GROOVES", "startTime": "18:00",
                  "acts": ["d_j_nair", "peaven.music", "Ben Klock"], "pricePresale": 12, "priceBoxOffice": "0",
                  "evidence": "Sunday 04.10.26", "confidence": 0.95}]}
                """.trimIndent(),
                caption = PRISMA,
                postedAt = "2026-10-04T09:00:00Z"
            )?.single()

        event?.startTime shouldBe LocalTime.of(18, 0)
        event?.acts shouldBe listOf("d_j_nair", "peaven.music")
        event?.pricePresale.shouldBeNull()
        event?.priceBoxOffice.shouldBeNull()
    }

    @Test
    fun `an act may be the name a handle in the caption spells`() {
        val event =
            parse(
                """
                {"isEvent": true, "events": [{"date": "2026-12-31", "title": "NULL | RESET",
                  "acts": ["Herve", "Henning Baer", "Amoral", "JASSS", "J As", "Ben Klock"],
                  "evidence": "From December 31st", "confidence": 0.95}]}
                """.trimIndent(),
                caption = "From December 31st through January 4th\n___herve\nhenningbaer_\namoral___dj\njasss_inc",
                postedAt = "2026-10-05T09:00:00Z"
            )?.single()

        event?.acts shouldBe listOf("Herve", "Henning Baer", "Amoral", "JASSS")
    }

    @Test
    fun `a quote that joins the line with the date to the line with the time is kept`() {
        // Café Cralle's answer on staging: the two lines are the caption's, the two between them are left out.
        val answer =
            """{"isEvent": true, "events": [{"date": "2026-10-31", "title": "Día de Muertos", "startTime": "19:00",
              "evidence": "SÁBADO, 31.10.2026\n🕖 A PARTIR DE LAS 19 H", "confidence": 0.95}]}"""

        val event = parse(answer, caption = CRALLE, postedAt = "2026-10-04T19:28:41Z")?.single()

        event?.date shouldBe LocalDate.of(2026, 10, 31)
        event?.startTime shouldBe LocalTime.of(19, 0)
    }

    @Test
    fun `with an image, the day, the time and the acts may come from the text read off it`() {
        val answer =
            """
            {"isEvent": true, "flyerText": "SELF.CONTROL.\nFR 16 Oct\n23:00 – 08:00\nCosmo Vitelli\nCYRK live\nModerna",
             "events": [{"date": "2026-10-16", "title": "Self. Control.", "startTime": "23:00",
              "acts": ["Cosmo Vitelli", "CYRK", "Moderna", "Ben Klock"], "evidence": "FR 16 Oct", "confidence": 0.9}]}
            """.trimIndent()

        val event = parse(answer, caption = FLYER_CAPTION, images = listOf(FLYER))?.single()

        event?.acts shouldBe listOf("Cosmo Vitelli", "CYRK", "Moderna")
        event?.startTime shouldBe LocalTime.of(23, 0)
        event?.flyerText shouldBe "SELF.CONTROL.\nFR 16 Oct\n23:00 – 08:00\nCosmo Vitelli\nCYRK live\nModerna"
    }

    @Test
    fun `a doors time stays apart from the start, and only when the post names its hour`() {
        val event =
            parse(
                """
                {"isEvent": true, "flyerText": "SELF.CONTROL.\nFR 16 Oct\nDoors 23:00",
                 "events": [{"date": "2026-10-16", "title": "Self. Control.", "doorsTime": "23:00", "startTime": "22:00",
                  "evidence": "FR 16 Oct", "confidence": 0.9}]}
                """.trimIndent(),
                caption = FLYER_CAPTION,
                images = listOf(FLYER)
            )?.single()

        event?.doorsTime shouldBe LocalTime.of(23, 0)
        event?.startTime.shouldBeNull()
    }

    @Test
    fun `without an image, text the answer claims to have read off one counts for nothing`() {
        parse(
            """{"isEvent": true, "flyerText": "FR 16 Oct", "events": [{"date": "2026-10-16", "title": "x", "evidence": "FR 16 Oct", "confidence": 0.9}]}""",
            caption = FLYER_CAPTION
        )?.shouldBeEmpty()
    }

    @Test
    fun `a post with no caption is drafted from its image alone`() {
        parse(
            """
            {"isEvent": true, "flyerText": "XTRUDE SAT 17 OCT",
             "events": [{"date": "2026-10-17", "title": "XTRUDE", "evidence": "SAT 17 OCT", "confidence": 0.9}]}
            """.trimIndent(),
            caption = "",
            images = listOf(FLYER)
        )?.map { it.title } shouldBe listOf("XTRUDE")
    }

    @Test
    fun `a price the caption names survives`() {
        val event =
            parse(
                """{"isEvent": true, "events": [{"date": "2026-10-24", "title": "Bunte Party-Nacht", "pricePresale": "8,00",
                   "evidence": "Samstag, den 24.10.2026", "confidence": 0.9}]}""",
                caption = "Nächsten Samstag, den 24.10.2026, ab 21:00 Uhr. Eintritt 8 Euro.",
                postedAt = "2026-10-14T10:00:00Z"
            )?.single()

        event?.pricePresale shouldBe BigDecimal("8.00")
    }

    @Test
    fun `one night named twice in one post gives one event`() {
        parse(
            """
            {"isEvent": true, "events": [
              {"date": "2026-10-16", "title": "selfcontrolbln", "evidence": "on Friday", "confidence": 0.8},
              {"date": "2026-10-16", "title": "selfcontrolbln returns", "evidence": "on Friday", "confidence": 0.9}
            ]}
            """.trimIndent()
        )?.map { it.title } shouldBe listOf("selfcontrolbln returns")
    }

    @Test
    fun `a post that announces nothing is an empty list, and no JSON is null`() {
        parse("""{"isEvent": false, "events": []}""")?.shouldBeEmpty()
        parse("These are two events.").shouldBeNull()
    }

    private companion object {
        val FLYER = PostImage(byteArrayOf(1), MediaType.IMAGE_JPEG)

        const val CRALLE =
            "🌼💀 DÍA DE MUERTOS 💀🌼\n\n📅 SÁBADO, 31.10.2026\n📍 CAFÉ CRALLE\nHochstädter Str. 10A, 13347 Berlín\n🕖 A PARTIR DE LAS 19 H"

        const val FLYER_CAPTION = "selfcontrolbln returns to the SUMME floor✖️ / Artwork by dominikkeller_of"

        const val RSO_WEEK =
            "Coming up this week — We host selfcontrolbln on the SUMME floor on Friday and then on Saturday we team up with " +
                "elli.acula’s Euphoria Sessions as we round out the weekend with XTRUDE✖️"

        const val PRISMA =
            "SUNSET GROOVES prisma.klubkneipe\n📍 Prisma Bar\n🕕 18:00–02:00\n🗓 Sunday 04.10.26\n🎟️ Free entry\n" +
                "d_j_nair , gojas___ & peaven.music are bringing warm Afro House"
    }
}
