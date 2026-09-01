package de.norm.events

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Carries `@EnableScheduling`, so `app.scheduling.enabled=false` can stop every scheduled task.
 *
 * **Necessary and not sufficient, which is the trap #949 turned on.** A dependency also switches
 * scheduling on: `spring-modulith-moments` carries its own `@EnableScheduling`, so the
 * `ScheduledAnnotationBeanPostProcessor` is registered whatever this application asks for. The test
 * `application.yaml` therefore also sets `spring.modulith.moments.enabled: false`, and
 * `SchedulingDisabledInTestsTest` asserts the processor is absent rather than that this switch is
 * off — the assertion that catches the next dependency to do the same.
 *
 * It sits here rather than on [EventsImporterApplication] because the gauge refreshers are plain
 * services that two integration tests autowire and call, so gating the beans is not available.
 *
 * `matchIfMissing = true` is load-bearing: nothing in `deploy/` sets this, so the running importer
 * schedules because of this default.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration
