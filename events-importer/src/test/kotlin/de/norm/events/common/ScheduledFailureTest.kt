package de.norm.events.common

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * What Spring does when a `@Scheduled` suspend function throws: it logs a `WARN` and runs the next
 * tick. The catch-alls in [runScheduled] and its siblings exist for the log line, not to keep the
 * schedule alive. A Spring upgrade that changes this fails here.
 */
class ScheduledFailureTest {
    @Test
    fun `a suspend function that throws keeps its fixed-delay schedule`() {
        ticksWith(Failing::class.java) shouldBe true
    }

    @Test
    fun `a suspend function that throws a stray CancellationException keeps its schedule`() {
        ticksWith(StrayCancellation::class.java) shouldBe true
    }

    private fun ticksWith(configuration: Class<*>): Boolean =
        AnnotationConfigApplicationContext(configuration).use { context ->
            context.getBean(Ticks::class.java).latch.await(10, TimeUnit.SECONDS)
        }

    class Ticks {
        val latch = CountDownLatch(3)
    }

    @Configuration
    @EnableScheduling
    open class Failing {
        @Bean
        open fun ticks() = Ticks()

        @Bean
        open fun job(ticks: Ticks) =
            object {
                @Scheduled(fixedDelay = 10)
                suspend fun tick() {
                    ticks.latch.countDown()
                    error("database gone")
                }
            }
    }

    @Configuration
    @EnableScheduling
    open class StrayCancellation {
        @Bean
        open fun ticks() = Ticks()

        @Bean
        open fun job(ticks: Ticks) =
            object {
                @Scheduled(fixedDelay = 10)
                suspend fun tick() {
                    ticks.latch.countDown()
                    throw CancellationException("a cancelled future's")
                }
            }
    }
}
