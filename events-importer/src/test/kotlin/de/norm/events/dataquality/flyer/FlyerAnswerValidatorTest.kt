package de.norm.events.dataquality.flyer

import de.norm.events.ai.UnparseableAnswer
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import tools.jackson.databind.json.JsonMapper
import java.time.LocalDate
import java.time.LocalTime

class FlyerAnswerValidatorTest {
    @Test
    fun `acts and a start time that occur in the flyer text are kept`() {
        val answer =
            FlyerAnswerValidator
                .parse(
                    json(
                        "flyerText" to SAVORY,
                        "acts" to finding(listOf("Lukr Range", "Taube", "Wan"), "LUKR RANGE\nTAUBE\nWAN"),
                        "startTime" to finding("23:00", "23:00 — END")
                    ),
                    NIGHT
                ).shouldNotBeNull()

        answer.acts!!.value shouldContainExactly listOf("Lukr Range", "Taube", "Wan")
        answer.startTime!!.value shouldBe LocalTime.of(23, 0)
        answer.flyerText shouldBe SAVORY
    }

    @Test
    fun `an act the flyer text does not hold is dropped, and a repeated one is kept once`() {
        val answer =
            FlyerAnswerValidator
                .parse(
                    json("flyerText" to SAVORY, "acts" to finding(listOf("Taube", "TAUBE", "Ben Klock"), "TAUBE")),
                    NIGHT
                ).shouldNotBeNull()

        answer.acts!!.value shouldContainExactly listOf("Taube")
    }

    @Test
    fun `a time whose hour the evidence does not name, or evidence the text does not hold, is dropped`() {
        val wrongHour =
            FlyerAnswerValidator.parse(json("flyerText" to SAVORY, "startTime" to finding("22:00", "23:00 — END")), NIGHT).shouldNotBeNull()
        wrongHour.startTime.shouldBeNull()

        val invented =
            FlyerAnswerValidator.parse(json("flyerText" to SAVORY, "acts" to finding(listOf("Taube"), "Line up: Taube")), NIGHT).shouldNotBeNull()
        withClue("the evidence is not a passage of the flyer text") { invented.acts.shouldBeNull() }
    }

    @Test
    fun `a confidence outside 0 to 1 drops the finding`() {
        val answer =
            FlyerAnswerValidator.parse(json("flyerText" to SAVORY, "acts" to finding(listOf("Taube"), "TAUBE", 1.5)), NIGHT).shouldNotBeNull()

        answer.acts.shouldBeNull()
    }

    @Test
    fun `a flyer text that does not name the event's day proposes nothing`() {
        val otherNight = SAVORY.replace("19 NOVEMBER 2026", "26 NOVEMBER 2026")

        FlyerAnswerValidator.parse(json("flyerText" to otherNight, "acts" to finding(listOf("Taube"), "TAUBE")), NIGHT).shouldBeNull()
    }

    @Test
    fun `an empty or overlong flyer text proposes nothing`() {
        FlyerAnswerValidator.parse(json("flyerText" to "  "), NIGHT).shouldBeNull()
        FlyerAnswerValidator.parse(json("flyerText" to "19.11. " + "x".repeat(FlyerAnswerValidator.MAX_FLYER_TEXT)), NIGHT).shouldBeNull()
    }

    @Test
    fun `an answer that is no JSON throws, so the caller can count it`() {
        shouldThrow<UnparseableAnswer> { FlyerAnswerValidator.parse("I see a flyer.", NIGHT) }
    }

    @ParameterizedTest
    @ValueSource(strings = ["21.11.", "fr 21.nov", "SA/21/NOV", "21 November 2026", "NOV 21ST", "21. Nov. 26", "21/11"])
    fun `the day is named in the shapes the sampled flyers print`(text: String) {
        EventDay.namedIn(LocalDate.of(2026, 11, 21), text) shouldBe true
    }

    @ParameterizedTest
    @ValueSource(strings = ["121.11.", "21.12.", "Nov 2", "2026-11-12", "ab 21 Uhr", "21:11"])
    fun `another day, another month or a time is no day`(text: String) {
        EventDay.namedIn(LocalDate.of(2026, 11, 21), text) shouldBe false
    }

    @Test
    fun `German and English month names both count`() {
        EventDay.namedIn(LocalDate.of(2026, 12, 4), "04.Dez") shouldBe true
        EventDay.namedIn(LocalDate.of(2026, 12, 4), "Dec 4th") shouldBe true
        EventDay.namedIn(LocalDate.of(2027, 3, 6), "6. März") shouldBe true
        EventDay.namedIn(LocalDate.of(2027, 10, 9), "9. Okt") shouldBe true
    }

    private companion object {
        val NIGHT: LocalDate = LocalDate.of(2026, 11, 19)
        val mapper: JsonMapper = JsonMapper.builder().build()

        /** The Club OST flyer of the 2026-11-19 sample in #5048. */
        val SAVORY =
            """
            SAVORY
            19 NOVEMBER 2026
            FREE ENTRY UNTIL 00:30
            23:00 — END
            LINE UP A—Z
            LUKR RANGE
            TAUBE
            WAN
            ALT-STRALAU 1-2
            10245 BERLIN
            """.trimIndent()

        fun finding(
            value: Any,
            evidence: String,
            confidence: Double = 0.9
        ) = mapOf("value" to value, "evidence" to evidence, "confidence" to confidence)

        fun json(vararg entries: Pair<String, Any>): String = mapper.writeValueAsString(mapOf(*entries))
    }
}
