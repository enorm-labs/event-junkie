package de.norm.events.weather

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WeatherConditionTest {
    @Test
    fun `each WMO range MOSMIX uses reads as its icon`() {
        mapOf(
            0 to WeatherCondition.CLEAR,
            2 to WeatherCondition.PARTLY_CLOUDY,
            3 to WeatherCondition.CLOUDY,
            45 to WeatherCondition.FOG,
            53 to WeatherCondition.RAIN,
            81 to WeatherCondition.RAIN,
            71 to WeatherCondition.SNOW,
            86 to WeatherCondition.SNOW,
            95 to WeatherCondition.THUNDERSTORM,
            100 to null
        ).forEach { (ww, condition) -> WeatherCondition.of(ww) shouldBe condition }
    }
}
