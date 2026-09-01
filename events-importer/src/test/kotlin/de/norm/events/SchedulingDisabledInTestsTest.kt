package de.norm.events

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor

/**
 * Asserts that **no `@Scheduled` method can fire** in this module's test contexts.
 *
 * **This asserts the effect, not the switch, and #949 is why.** [SchedulingConfiguration] carries the
 * `app.scheduling.enabled` condition and that condition works — but it was never the only thing
 * enabling the scheduler. `spring-modulith-moments`' `MomentsAutoConfiguration` carries its own
 * `@EnableScheduling`, so `ScheduledAnnotationBeanPostProcessor` was registered anyway and every
 * `@Scheduled` method kept running with the switch off. A test asserting the switch passed happily
 * while the suite kept deadlocking.
 *
 * The processor is the one thing that cannot be present if nothing is to fire, whichever library
 * asked for it. A dependency that starts enabling scheduling fails here, in the module whose suite it
 * would otherwise make intermittently red.
 *
 * The cost of getting it wrong is `40P01`: a gauge refresh firing on context startup, against the
 * tables [BaseControllerTest.cleanUp] truncates under ACCESS EXCLUSIVE, taking its own locks in the
 * opposite order. One arbitrary test of ~2,550 dies per run and passes on re-run.
 *
 * It extends [BaseControllerTest] on purpose: that shares the cached context every other controller
 * test uses, so this check adds no context and no container of its own.
 */
class SchedulingDisabledInTestsTest : BaseControllerTest() {
    @Autowired
    private lateinit var context: ApplicationContext

    @Test
    fun `no scheduled task processor is registered, so nothing fires during the suite`() {
        context
            .getBeanNamesForType(ScheduledAnnotationBeanPostProcessor::class.java)
            .toList()
            .shouldBeEmpty()
    }
}
