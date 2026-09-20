package de.norm.events

import io.micrometer.context.ContextRegistry
import jakarta.annotation.PostConstruct
import org.slf4j.MDC
import org.springframework.context.annotation.Configuration
import reactor.core.publisher.Hooks

/**
 * Makes the per-request log context survive the reactive chain (#380), the half of structured
 * logging that fails silently: MDC is thread-local and WebFlux runs one request across several
 * threads, so the line still appears with no context field. [ContextRegistry] teaches Reactor to
 * move [REQUEST_ID] between a thread-local and the subscriber context, and
 * [Hooks.enableAutomaticContextPropagation] makes it do so around every operator.
 * [RequestLoggingFilter] writes the value; `suspend` handlers read it.
 */
@Configuration
class LogContextConfiguration {
    /**
     * Both calls are process-wide and idempotent: they configure Reactor rather than produce a bean.
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
         * One id per HTTP request: Spring's own exchange id, the one a `ProblemDetail` body carries
         * under the same name (#1527). Named `requestId` and not `traceId`, since nothing here issues a
         * W3C trace context.
         */
        const val REQUEST_ID = "requestId"

        /**
         * The access line's own values, named at the call site rather than carried in MDC (#945): they
         * describe one line and reach the ECS JSON through `logger.at(…) { payload = … }`. Every name
         * here is also written in `transform/parse_structured_logs` in
         * `deploy/clusters/base/collector.yaml`, and nothing checks that the two agree
         * (`docs/ops/PLATFORM_SETUP.md` §7).
         */
        const val HTTP_METHOD = "httpMethod"

        /** The endpoint, without the query string. See [RequestLoggingFilter] for why. */
        const val PATH = "path"

        /**
         * The status we returned, as an Int: a string would settle the OpenObserve column as a string.
         * The importer spells the same name in `LogFields` for the status a venue's server returned to
         * us; `service_name` separates the two.
         */
        const val HTTP_STATUS = "httpStatus"

        /**
         * The object a storage warning is about, written only when one cannot be read (#980). High
         * cardinality, acceptable because the lines are rare.
         */
        const val STORAGE_KEY = "storageKey"
    }
}
