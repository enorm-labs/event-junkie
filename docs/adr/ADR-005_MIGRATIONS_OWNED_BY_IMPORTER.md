# ADR-005: Database Migrations Owned by the Importer Only

## Status

**Accepted — `events-importer` owns and runs every Flyway migration, and the BFF has no Flyway at all. A data fix is a migration in the same sequence.
There is no baseline until a fresh database takes more than 5 s to migrate.**

## Context

The system has two Spring Boot applications that access the same PostgreSQL database:

- **events-importer** — Writes data (imports events from external sources, admin CRUD).
- **events-bff** — Reads data (serves the frontend API).

Both could theoretically run Flyway migrations at startup. The question is which application should own and execute database migrations.

## Decision

**Only `events-importer` runs Flyway migrations.** The BFF does not include Flyway and does not modify the database schema.

- All migration files live in `events-importer/src/main/resources/db/migration/`.
- Migration naming follows `V001__description.sql`, `V002__description.sql`, etc.
- **Each schema change is its own migration.** `V001__create_initial_schema.sql` was consolidated while nothing was
  deployed. That window closed on 2026-08-19 — see below.
- The importer configures `spring.flyway.schemas: events` to target the dedicated schema.
- **A data fix is a `V` migration in the same sequence as the schema changes.** The rule for each kind of fix is in [Data fixes](#data-fixes-2446).
- **The history stays unsquashed.** The trigger and the procedure for a baseline are in [Baseline](#baseline-2446).

## Consequences

- **Positive**: one source of truth for schema changes, so two apps cannot write conflicting migrations. The BFF
  stays lightweight and read-focused, and starts faster for want of a migration check. Clear ownership makes it
  obvious where a new migration goes.
- **Negative**: a schema change means deploying or starting the importer before the BFF, or alongside it. In
  development, `events-importer` has to run before `events-bff` can reach a new table or column. Docker Compose dev
  services and Spring Boot's auto-start mitigate that locally.
- New tables or columns are always added via a migration in the importer module, even if they are primarily read by the BFF.

## Why the consolidation window is closed (#415)

Every schema change used to be folded back into `V001__create_initial_schema.sql` rather than added as `V002`. The
rule in AGENTS.md said _"while the project is in development (not yet deployed to production)."_ That was a
reasonable
trade. It kept the whole schema readable in one file. Re-reading a migration history nobody had ever applied would
have been ceremony.

**The trigger named the wrong event.** Consolidation depended on the absence of _any database with `V001` applied_, not
on the absence of _production_. Staging became one long before production will. Editing an applied migration produces
`FlywayValidateException: Migration checksum mismatch`. The importer's context then fails to start, the pod never
becomes Ready, and the HelmRelease's `remediateLastFailure: true` rolls the release back. The operator sees "the deploy
reverted", two layers away from the cause, on a change that looked like adding a column.

The window closed when three changes in flight were each editing `V001` at once. That is what a policy looks like once
it stops being free.

**Resetting a pre-launch environment is still the cheaper option, and will not be for long.** Every event in this
system is scraped from a public page, and every venue and source is re-creatable from
`http/importer/dev-seed.http`. Dropping the `events` schema therefore costs one import cycle rather than data. That
property ends the day anything is stored that was not derived. The reset stops being available on the same day, and
the migration history becomes the only way forward.

## Data fixes (#2446)

A data fix changes rows, not the schema. 57 of the first 89 migrations are data fixes. A migration is the right carrier for three reasons:

- It runs exactly once on each database, in order with the schema changes.
- It is reviewed in a pull request, and a test can run it against planted rows.
- It reaches staging and production with no operator step.

Staging and production are never re-seeded, and a past event is never imported again. So a parser fix never reaches the rows that past events
already link to. A data fix is the only route to those rows.

Each kind of fix goes to one place:

| Kind of fix                                                                                 | Examples                     | Where it goes                         |
| ------------------------------------------------------------------------------------------- | ---------------------------- | ------------------------------------- |
| One named row: drop, merge, split or relink a slug                                          | V044, V053, V073, V089       | A `V` migration                       |
| A row that one source billed by mistake, which the parser now drops                         | V087, V091                   | A `V` migration                       |
| A global name rule applied again to the rows stored before the rule existed (_rule-shaped_) | V045, V055, V067, V068, V072 | The repair pass, from the next one on |

**A per-source drop stays a migration.** The parser often decides from context that the stored row does not keep. Renate's `Performances by:` was
dropped because its line ended in a colon (V091). The stored name has no colon, so no pass over stored names can find that row again.

**The repair pass does not exist yet.** The next rule-shaped fix builds it instead of a migration. The pass runs `ArtistNormalizer` over every stored
`artist.name` and logs each change. It must be idempotent. It must merge a row whose new slug already exists, because a rename can collide with
another row. Five rule-shaped fixes in 89 migrations do not pay for that code in advance.

**Rejected:**

- **An admin endpoint or a hand-run `psql`.** Each runs once per environment, leaves no history, and becomes an `## After deploy` step that can be
  skipped.
- **A second Flyway location with its own history table.** The data fixes lose their order against the schema changes, and "applied once" then spans
  two histories.
- **Timestamp versions** (`V2026_10_03_1__`). A late merge of an older timestamp needs `outOfOrder: true`. `scripts/migration-versions.sh` and
  `migration-versions.yml` already catch two branches that take the same number (#2442).

## Baseline (#2446)

Flyway 12.4 supports baseline migrations in the open-source core, with the `B<version>__` prefix. A fresh database applies only the newest `B` file
and then the `V` files above it. An existing database ignores the `B` file and validates against its own history.

**There is no baseline now, because replay is cheap.** On 2026-10-03, a fresh Testcontainers database applied all 89 migrations in 0.528 s, 0.475 s
and 0.758 s, in three runs. The number of files alone is not a reason to baseline.

**Make a baseline when a fresh database takes more than 5 s to migrate.** Read the time from
`Successfully applied N migrations … (execution time …)` in `EventsImporterApplicationTests`, over three runs. Then do these steps:

1. At a release, add `B0NN__baseline.sql`. Make it with `pg_dump --schema-only` of the `events` schema at version `0NN`.
2. Rehearse the release against a copy of the production database.
3. At a later release, make sure that every cluster is past `0NN`.
4. Delete `V001` to `V0NN`, and set `spring.flyway.ignore-migration-patterns: "*:missing"`.
5. Delete each migration test that migrates to a version below `0NN`, together with its `V` file.

Do not edit `V001` in place to squash the history. The checksum mismatch in [Why the consolidation window is closed](#why-the-consolidation-window-is-closed-415) applies unchanged.

## References

- [Flyway documentation](https://documentation.red-gate.com/flyway)
- [events-importer/src/main/resources/db/migration/](../../events-importer/src/main/resources/db/migration/)
