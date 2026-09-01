package de.norm.events

import org.junit.jupiter.api.Test

/**
 * The application context starts.
 *
 * **It extends [BaseControllerTest] so it shares that context rather than forking one (#965).** It
 * used to be a bare `@SpringBootTest`, whose default MOCK web environment is a different context
 * configuration from every other test here — one more cached context, one more container, for an
 * assertion the rest of the suite makes implicitly on every run.
 *
 * What that trades away is honest and small: this now proves the `RANDOM_PORT` context starts rather
 * than the MOCK one. Production runs a real server, so the surviving assertion is the closer of the
 * two.
 */
class EventsImporterApplicationTests : BaseControllerTest() {
    @Test
    fun contextLoads() {
        // Intentionally empty - starting the context is the assertion.
    }
}
