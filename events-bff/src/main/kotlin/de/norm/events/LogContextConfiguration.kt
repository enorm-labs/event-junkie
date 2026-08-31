package de.norm.events

import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

/**
 * Makes the per-request log context survive the reactive chain (#380).
 *
 * **This is the half of structured logging that fails silently.** MDC is thread-local; WebFlux runs
 * one request across several threads, so a value put in a filter is simply gone by the time a
 * handler logs. The log line still appears — it just has no context field — and that reads as a
 * configuration problem rather than a threading one, which is the day #380 warns about.
 *
 * Two things are needed and neither works without the other. [ContextRegistry] teaches Reactor how
 * to move the [REQUEST_ID] between a thread-local and the subscriber context, and
 * [Hooks.enableAutomaticContextPropagation] is what makes it do so around every operator rather than
 * only where someone remembered to ask. [RequestLoggingFilter] writes the value; everything
 * downstream, including `suspend` handlers reached through `kotlinx-coroutines-reactor`, reads it.
 */
@Configuration
class LogContextConfiguration {
    /**
     * Both calls are process-wide and idempotent, which is why a lifecycle callback is the right
     * place for them: they configure Reactor itself rather than producing a bean, and running twice
     * costs nothing.
     */
    @PostConstruct
    fun enableRequestIdPropagation() {
        ContextRegistry
            .getInstance()
            .registerThreadLocalAccessor(REQUEST_ID, { MDC.get(REQUEST_ID) }, { value -> MDC.put(REQUEST_ID, value) }, { MDC.remove(REQUEST_ID) })
        Hooks.enableAutomaticContextPropagation()
    }

    companion object {
        /**
         * One id per HTTP request, so every line a request produced can be read together.
         *
         * Named `requestId` and not `traceId` on purpose: nothing here issues a W3C trace context,
         * and a field named after a tracing system that is not on the classpath would invite joins
         * that cannot work.
         */
        const val REQUEST_ID = "requestId"
    }
}
