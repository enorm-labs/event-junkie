package de.norm.events.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalTime

class ModelAnswerTest {
    @Test
    fun `an hour is named on either clock, with or without minutes and a leading zero`() {
        ModelAnswer.namesHour(LocalTime.of(20, 0), "Beginn 20 Uhr") shouldBe true
        ModelAnswer.namesHour(LocalTime.of(21, 0), "starts at 9pm") shouldBe true
        ModelAnswer.namesHour(LocalTime.of(19, 30), "Einlass 19.30") shouldBe true
        ModelAnswer.namesHour(LocalTime.of(9, 0), "Brunch ab 09:00") shouldBe true
    }

    @Test
    fun `an hour inside another number is not named`() {
        ModelAnswer.namesHour(LocalTime.of(21, 0), "Einlass 19 Uhr") shouldBe false
        ModelAnswer.namesHour(LocalTime.of(20, 0), "2026") shouldBe false
    }

    @Test
    fun `a price is named by its whole euros, and not by the digits of another price`() {
        ModelAnswer.namesPrice(BigDecimal("18.50"), "VVK 18,50 €") shouldBe true
        ModelAnswer.namesPrice(BigDecimal("12.00"), "12/15 €") shouldBe true
        ModelAnswer.namesPrice(BigDecimal("5.00"), "Eintritt 15 €") shouldBe false
        ModelAnswer.namesPrice(BigDecimal("50.00"), "VVK 18,50 €") shouldBe false
    }
}
