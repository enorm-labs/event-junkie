package de.norm.events

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Provides named [CoroutineDispatcher] beans for constructor injection.
 *
 * Externalizing dispatchers as beans avoids hardcoded `Dispatchers.*` references
 * (flagged by detekt) and prevents Spring from accidentally injecting an unrelated
 * dispatcher when a constructor parameter has a default value.
 */
@Configuration
class CoroutineConfiguration {
    /**
     * IO dispatcher for offloading blocking/CPU-bound work (e.g. Jsoup HTML parsing).
     *
     * `destroyMethod = ""` prevents Spring from attempting to call `close()` on
     * [Dispatchers.IO] during shutdown — it is a global singleton that cannot be closed.
     */
    @Suppress("InjectDispatcher") // This bean *is* the injection point every caller resolves the dispatcher through.
    @Bean("ioDispatcher", destroyMethod = "")
    fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
}
