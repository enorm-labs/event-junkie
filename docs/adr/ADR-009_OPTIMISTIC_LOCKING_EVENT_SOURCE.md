# ADR-009: Optimistic Locking for EventSourceEntity

## Status

**Accepted — `@Version` on `EventSourceEntity` alone, with the mutual exclusion done by a conditional `UPDATE`.**

## Context

The `event_source` table is susceptible to concurrent modifications from multiple writers:

1. **Scheduled imports** — the `ScheduledImportService` ticks every 60 seconds and picks up due sources.
2. **Manual triggers** — admins can trigger an import via `POST /api/admin/event-sources/{slug}/import`.
3. **Admin PATCH** — configuration changes (enable/disable, interval, retries) via `PATCH /api/admin/event-sources/{slug}`.

Without concurrency control, a race condition can cause **lost updates**. For example:

- The scheduler reads source A (version N) and starts an import.
- An admin PATCHes source A (writing version N+1) to disable it.
- The scheduler finishes and writes `markSuccess` using its stale copy (version N), overwriting the admin's `enabled = false`.

The `EventImportService` also threads entity state through multiple save operations in sequence (claim → business logic → `markSuccess`/`markFailed`/
`markMisconfigured`). Each step must operate on the latest persisted state to avoid overwriting intermediate changes.

The other entities are single-writer: `VenueEntity`, `ArtistEntity`, `PromoterEntity` and `EventEntity`, through
admin CRUD or an importer upsert. No realistic concurrent modification reaches them, so they do not justify the
overhead of optimistic locking.

## Decision

Add **Spring Data `@Version` optimistic locking** to `EventSourceEntity` only.

- A `version BIGINT NOT NULL DEFAULT 0` column is added to the `event_source` table.
- The entity declares `@Version val version: Long? = null`.
- Spring Data R2DBC automatically increments the version on each save and includes
  `WHERE version = ?` in UPDATE statements.
- If a concurrent modification is detected, Spring throws `OptimisticLockingFailureException`.

The import run's opening RUNNING transition returns the claimed entity. The status updates that follow —
`markSuccess`, `markFailed`, `markMisconfigured` — therefore operate on the latest version, and no stale write reaches
the row.

**Optimistic locking does not, and cannot, prevent two runs importing the same source.** Two writers each calling
`save()` to mark a source RUNNING produce a version conflict. The retry-on-conflict strategy below resolves it by
re-fetching and retrying, so the second write succeeds and the duplicate run proceeds. Mutual exclusion needs a
_conditional_ write, which the database decides. `EventSourceRepository.claimForImport` issues
`UPDATE … SET status = 'RUNNING' … WHERE id = :id AND version = :expectedVersion AND status <> 'RUNNING'` and returns
the affected row count. Exactly one caller claims a source, and the loser sees `0` and skips its run. Optimistic
locking remains for its actual purpose: protecting an in-flight import's metadata from a concurrent admin edit.

The `version = :expectedVersion` half of that guard makes the claim a **compare-and-swap against the exact row the
caller read**. It is not redundant with the status check. A status-only guard excludes runs that _overlap_. It does
not exclude one that starts the instant another finishes. Imports queue on a bounded semaphore
(`app.import.max-concurrency`), so a source can sit between "read" and "claimed" for a long time. Long enough to still
look IDLE to a scheduler tick, with `last_import_at IS NULL`. A manual trigger imports it, and the tick then re-claims
it, because the status is back at SUCCESS. The result is a second full scrape of the same venue, which is exactly what
ADR-007's politeness constraints exist to avoid. Any completed run bumps the version, so gating on it makes the stale
caller lose.

Gating on the version also makes the claim's `version + 1` **exact**. The entity returned to the caller carries the
version actually persisted rather than an in-memory guess. The closing `markSuccess` or `markFailed` save then matches
on its first attempt. Without the gate, a stale caller's guess is wrong by however many writes it missed. Every such
run logs an `OptimisticLockingFailureException` warning on the way to being repaired by the retry below.

A version conflict can still occur. An external writer such as `ScheduledImportService.resetStuckSources()` modifies
the `event_source` row between the claim and the status update that follows. Every status update method therefore uses
a **retry-on-conflict** strategy. On `OptimisticLockingFailureException` it re-fetches the entity by ID to obtain the
current version, re-applies the status mutation, and retries the save once. If the retry also fails the exception
propagates, and the scheduler picks the source up on the next tick.

## Consequences

### Positive

- **Prevents lost updates** — concurrent modifications fail fast rather than silently overwriting each other.
- **No distributed locks needed** — optimistic locking is lightweight and works with any PostgreSQL setup. No
  advisory locks, no external coordination.
- **Self-healing** — a status update retries once on a version conflict, by re-fetching the entity. If the retry also
  fails, the exception propagates harmlessly, and the scheduler picks the source up on the next tick.
- **Future-proof** — if more concurrent writers are added (e.g. webhooks, multiple importer instances), the protection is already in place.

### Negative

- **Slight overhead** — each UPDATE includes a version check, but this is negligible for the low write frequency of event sources.
- **Conflict handling** — `OptimisticLockingFailureException` propagates up to the caller. For the scheduler, this is acceptable (retry next tick). For admin
  API endpoints, the global exception handler could optionally map it to `409 Conflict`, but this is deferred until needed.

### Not Applied To

- **VenueEntity / ArtistEntity / PromoterEntity** — single-writer admin CRUD, no concurrent updates.
- **EventEntity** — upserted by a single importer per source through `sourceId`, with no competing writers.
- **EventArtistEntity / EventPromoterEntity** — join tables managed via delete-and-recreate, never updated in place.

## References

- [Spring Data R2DBC Optimistic Locking](https://docs.spring.io/spring-data/relational/reference/r2dbc/entity-persistence.html#r2dbc.optimistic-locking)
- ADR-008: Import Job Scheduling (describes the concurrent access patterns)
