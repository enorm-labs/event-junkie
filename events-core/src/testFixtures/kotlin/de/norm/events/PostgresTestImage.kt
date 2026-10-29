package de.norm.events

/**
 * The PostgreSQL image every Testcontainers test in both applications starts (#4222).
 *
 * It equals the `postgres` image in `compose.yaml`, so tests and local development meet the same
 * server. Dependabot moves `compose.yaml` and reads no Kotlin, so `PostgresTestImageTest` fails its
 * pull request until this line moves with it.
 */
const val POSTGRES_TEST_IMAGE = "postgres:18.6-alpine"
