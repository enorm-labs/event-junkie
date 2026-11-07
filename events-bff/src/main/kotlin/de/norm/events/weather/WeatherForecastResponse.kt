package de.norm.events.weather

import io.swagger.v3.oas.annotations.media.Schema

/** What the sky does in an hour, reduced from the WMO 4677 code MOSMIX gives to the icons the page draws. */
enum class WeatherCondition {
    CLEAR,
    PARTLY_CLOUDY,
    CLOUDY,
    FOG,
    RAIN,
    SNOW,
    THUNDERSTORM
    ;

    companion object {
        /**
         * MOSMIX states clear to overcast as `ww` 0 to 3, and precipitation, fog and storms with the WMO codes of present weather.
         * A code between the ranges MOSMIX uses is read as its nearest class, and an unknown one as no forecast.
         */
        @Suppress("MagicNumber") // The WMO 4677 code ranges, named by the branch they lead to.
        fun of(ww: Int): WeatherCondition? =
            when (ww) {
                0 -> CLEAR
                1, 2 -> PARTLY_CLOUDY
                3 -> CLOUDY
                in 40..49 -> FOG
                in 50..67, in 80..84 -> RAIN
                in 68..79, in 85..90 -> SNOW
                in 91..99 -> THUNDERSTORM
                in 4..39 -> CLOUDY
                else -> null
            }
    }
}

/** The DWD forecast for the hour an open-air event starts (ADR-057). */
@Schema(description = "The DWD MOSMIX forecast for the hour the event starts, at station 10384 (Berlin-Tempelhof)")
data class WeatherForecastResponse(
    @Schema(description = "The weather in the start hour, reduced to the icon the page shows", example = "PARTLY_CLOUDY")
    val condition: WeatherCondition,
    @Schema(description = "The temperature at the start, in whole degrees Celsius", example = "22")
    val temperatureCelsius: Int,
    @Schema(description = "The probability of more than 0.1 mm of rain in the start hour, in percent. Null when the forecast leaves it out", example = "10")
    val rainProbabilityPercent: Int?
)
