package de.norm.events.detekt

import dev.detekt.api.Config
import dev.detekt.api.Entity
import dev.detekt.api.Finding
import dev.detekt.api.Rule
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtCatchClause
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtIsExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtTryExpression
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType

/**
 * Reports a `catch` of `Exception`, `Throwable`, `RuntimeException` or `IllegalStateException`
 * inside a `suspend fun` when the same `try` catches no `CancellationException` before it and its
 * body does not rethrow one (#4638). Such a catch also takes the coroutine's cancellation: the run
 * logs a failure and goes on.
 *
 * detekt's `SuspendFunSwallowedCancellation` reported none of the four importer sites #4638 fixed,
 * type-resolved or not. This rule is syntax-level, so it cannot see which calls suspend: it reports
 * every catch-all in a suspend function, lambdas inside it included, since an inline lambda such as
 * `forEach`'s runs in the same coroutine.
 */
class CatchAllSwallowsCancellation(
    config: Config
) : Rule(config, "A catch-all in a suspend function also catches CancellationException.") {
    override fun visit(root: KtFile) {
        super.visit(root)
        root.collectDescendantsOfType<KtTryExpression>().filter { it.inSuspendFunction() }.forEach { tryExpression ->
            val clauses = tryExpression.catchClauses
            val firstCatchAll = clauses.indexOfFirst { it.typeName() in CATCH_ALL_TYPES }
            if (firstCatchAll < 0) return@forEach
            val handled = clauses.take(firstCatchAll).any { it.typeName() == CANCELLATION } || clauses[firstCatchAll].testsForCancellation()
            if (!handled) {
                val clause = clauses[firstCatchAll]
                report(
                    Finding(
                        Entity.from(clause),
                        "catch (${clause.typeName()}) in a suspend function also catches CancellationException. " +
                            "Add `catch (e: CancellationException) { throw e }` before it."
                    )
                )
            }
        }
    }

    private fun KtTryExpression.inSuspendFunction(): Boolean = getStrictParentOfType<KtNamedFunction>()?.hasModifier(KtTokens.SUSPEND_KEYWORD) == true

    private fun KtCatchClause.typeName(): String? =
        catchParameter
            ?.typeReference
            ?.text
            ?.substringAfterLast('.')

    /** Whether the catch-all's body tests for cancellation itself: `is CancellationException`, or a call that rethrows it. */
    private fun KtCatchClause.testsForCancellation(): Boolean {
        val body = catchBody ?: return false
        return body.collectDescendantsOfType<KtIsExpression>().any { it.typeReference?.text?.endsWith(CANCELLATION) == true } ||
            body.collectDescendantsOfType<KtCallExpression>().any { it.calleeExpression?.text in RETHROWING_CALLS }
    }

    private companion object {
        const val CANCELLATION = "CancellationException"

        // `rethrowIfCancelled` is common/RunCatchingCancellable.kt's wrapper of `ensureActive`.
        val RETHROWING_CALLS = setOf("rethrowIfCancelled", "ensureActive")

        // CancellationException is an IllegalStateException, so each of these catches it.
        val CATCH_ALL_TYPES = setOf("Exception", "Throwable", "RuntimeException", "IllegalStateException")
    }
}
