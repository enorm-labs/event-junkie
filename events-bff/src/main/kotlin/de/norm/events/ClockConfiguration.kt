package de.norm.events

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/**
 * The one [Clock] the BFF reads "today" from.
 *
 * `LocalDate.now()` resolves to the JVM's default zone, and so does this bean, so nothing moves.
 * What it buys is a seam: the seeded tiebreak in `EventSearchRepository` is a function of the date,
 * and a test can only show that two days order a tie differently by handing it two fixed clocks.
 */
@Configuration
class ClockConfiguration {
    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()
}
