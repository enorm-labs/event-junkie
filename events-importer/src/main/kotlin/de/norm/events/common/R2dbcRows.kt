package de.norm.events.common

import io.r2dbc.spi.Readable
import org.springframework.r2dbc.core.DatabaseClient

/**
 * Reads a column a hand-written `SELECT` declares NOT NULL, naming it when it is absent.
 *
 * A null means the projection and its mapping have drifted apart. Saying which column drifted
 * beats the bare `NullPointerException` that `!!` would raise.
 */
fun <T : Any> Readable.required(
    column: String,
    type: Class<T>
): T = requireNotNull(get(column, type)) { "Column '$column' is null" }

fun Readable.string(column: String): String = required(column, String::class.java)

fun Readable.long(column: String): Long = required(column, Number::class.java).toLong()

/** Binds [value], or a typed null: R2DBC cannot infer the column type from a bare null. */
fun <T : Any> DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: T?,
    type: Class<T>
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
