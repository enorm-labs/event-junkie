package de.norm.events.detekt

import dev.detekt.test.TestConfig
import dev.detekt.test.lint
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class CatchAllSwallowsCancellationTest {
    private val rule = CatchAllSwallowsCancellation(TestConfig())

    @Test
    fun `reports a catch-all in a suspend function`() {
        val findings =
            rule.lint(
                """
                suspend fun f() {
                    try {
                        g()
                    } catch (e: Exception) {
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings shouldHaveSize 1
        findings.single().message shouldContain "catch (Exception)"
    }

    @Test
    fun `reports a catch-all inside a lambda of a suspend function`() {
        val findings =
            rule.lint(
                """
                suspend fun f(urls: List<String>) {
                    urls.forEach { url ->
                        try {
                            g(url)
                        } catch (e: Throwable) {
                            println(e)
                        }
                    }
                }
                """.trimIndent()
            )

        findings shouldHaveSize 1
    }

    @Test
    fun `accepts a catch-all after a cancellation catch`() {
        val findings =
            rule.lint(
                """
                import kotlinx.coroutines.CancellationException

                suspend fun f() {
                    try {
                        g()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a catch-all that rethrows cancellation itself`() {
        val findings =
            rule.lint(
                """
                suspend fun f() {
                    try {
                        g()
                    } catch (e: Exception) {
                        if (e is kotlin.coroutines.cancellation.CancellationException) throw e
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a catch-all that calls rethrowIfCancelled`() {
        val findings =
            rule.lint(
                """
                suspend fun f() {
                    try {
                        g()
                    } catch (e: Exception) {
                        rethrowIfCancelled()
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a catch-all in a function that does not suspend`() {
        val findings =
            rule.lint(
                """
                fun f() {
                    try {
                        g()
                    } catch (e: Exception) {
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a narrow catch in a suspend function`() {
        val findings =
            rule.lint(
                """
                suspend fun f() {
                    try {
                        g()
                    } catch (e: java.io.IOException) {
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }

    @Test
    fun `accepts a suppressed catch-all`() {
        val findings =
            rule.lint(
                """
                @Suppress("CatchAllSwallowsCancellation")
                suspend fun f() {
                    try {
                        g()
                    } catch (e: Exception) {
                        println(e)
                    }
                }
                """.trimIndent()
            )

        findings.shouldBeEmpty()
    }
}
