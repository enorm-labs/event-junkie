package de.norm.events

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/**
 * Carries `@EnableScheduling`, so `app.scheduling.enabled=false` stops every scheduled task. The tests
 * set it and call the jobs directly. `matchIfMissing = true`: nothing in `deploy/` sets it.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["app.scheduling.enabled"], havingValue = "true", matchIfMissing = true)
class SchedulingConfiguration
