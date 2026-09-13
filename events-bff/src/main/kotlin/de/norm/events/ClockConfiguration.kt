package de.norm.events

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

/**
 * The one [Clock] the BFF reads "today" from, in Berlin.
 *
 * The containers run in UTC and nothing sets `TZ`, so a `systemDefaultZone()` clock flipped the
 * listing's day at 01:00 or 02:00 Berlin while the frontend's `todayIso` flipped it at midnight
 * (#299). Every event is in Berlin, so the day is Berlin's. The bean is also the seam the tests
 * use: the seeded tiebreak (#1380) and the late-night grace (#299) are functions of the clock.
 */
@Configuration
class ClockConfiguration {
    @Bean
    fun clock(): Clock = Clock.system(BERLIN)

    companion object {
        val BERLIN: ZoneId = ZoneId.of("Europe/Berlin")
    }
}
