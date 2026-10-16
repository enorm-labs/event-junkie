package de.norm.events.common

import org.springframework.r2dbc.core.DatabaseClient

/** Binds every entry of [params] by name, for the search repositories that build their SQL and bind values together. */
fun DatabaseClient.GenericExecuteSpec.bindAll(params: Map<String, Any>): DatabaseClient.GenericExecuteSpec =
    params.entries.fold(this) { spec, (key, value) -> spec.bind(key, value) }
