# Backups

What protects the database, what each layer actually survives, and how you know any of it is working.

**If something already went wrong, you want [RESTORE_RUNBOOK.md](RESTORE_RUNBOOK.md), not this.** This document is the design and the reasoning. That one is
the procedure, written for someone who is under pressure.

[ADR-012](../adr/ADR-012_CLOUD_PLATFORM.md) chose to own PostgreSQL rather than rent a managed one. It named this **the load-bearing mitigation of that trade,
and the single highest-risk item the decision creates**. Everything here exists because of that sentence.

## The short version

|                 |                                                                                                                            |
| --------------- | -------------------------------------------------------------------------------------------------------------------------- |
| **What**        | PostgreSQL only — WAL streamed continuously, plus a base backup nightly at 02:30                                           |
| **With**        | [`wal-g`](https://wal-g.readthedocs.io/PostgreSQL/) v3.0.8, installed by `infra/modules/environment/cloud-init/backups.sh` |
| **Where**       | `s3://event-junkie-backups/<environment>/`, Hetzner Object Storage, `fsn1`                                                 |
| **Window**      | 30 days of point-in-time recovery                                                                                          |
| **RPO**         | ≤ 5 minutes (`archive_timeout = 300`), lower under load                                                                    |
| **RTO**         | ~12 s measured on a 39 MB cluster — see §8, and do not trust that number as it grows                                       |
| **Verified by** | `walg check`, hourly: a backup exists, is younger than 26 hours, and the volume is under 85%                               |
| **Rehearsed**   | 2026-08-18, staging, both full replay and PITR — [#270](https://github.com/enorm-labs/event-junkie/issues/270)             |

## 1. What is backed up, and what is not

**Backed up: the `events` database, and nothing else.** That is the deliberate scope. Everything else in this system is either declared in Git or reproducible
from it. A backup of something reproducible is a second source of truth to keep in sync.

**Not backed up, and none of it is an oversight:**

| Not backed up                         | Because                                                                                                                       | Recovered by                                 |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| The k3s cluster                       | Declared in `infra/`, reconciled by Flux                                                                                      | Rebuild the node, re-bootstrap               |
| Flux's own state                      | It is a mirror of this repository                                                                                             | `flux bootstrap`                             |
| The `events-db` and `hetzner` Secrets | Hand-made, deliberately outside state ([#416](https://github.com/enorm-labs/event-junkie/issues/416) replaces them with SOPS) | Re-created by hand — CLUSTER_BOOTSTRAP.md §8 |
| `/etc/wal-g/credentials.env`          | Same reason, and see §5 — this one bites                                                                                      | Re-created by hand — §8b                     |
| The OpenTofu state bucket             | Versioned instead, 90-day non-current expiry                                                                                  | `infra/README.md`                            |
| Scraped source pages                  | Re-scrapable by definition; the importers exist to do exactly this                                                            | An import run                                |

**The last row is worth pausing on.** Losing the database is not losing the data permanently — the importers can rebuild most of it from the venues' own sites.
What it _does_ lose is everything derived and everything historical. Artist and venue identity resolution, the mappings, anything a source took down since,
and the record of what was true when. That is the loss backups are actually preventing, and it is not visible in a row count.

## 2. Three layers, and what each one survives

They fail differently, and none of them substitutes for another. This is the table to read before assuming you are covered.

| Failure                                                     | PGDATA volume                                 | `wal-g` → Object Storage      | Hetzner server snapshots       |
| ----------------------------------------------------------- | --------------------------------------------- | ----------------------------- | ------------------------------ |
| The node dies or is replaced                                | ✅ survives, adopted on next boot             | ✅                            | ✅                             |
| `DROP TABLE`, a bad migration, an importer bug that deletes | ❌ faithfully preserves the damage            | ✅ **point-in-time recovery** | 🟡 to the last daily snapshot  |
| Filesystem or disk corruption                               | ❌                                            | ✅                            | 🟡 if the snapshot predates it |
| `tofu destroy` on the environment                           | ❌ `delete_protection` does not stop OpenTofu | ✅ off-server entirely        | ❌ taken with the server       |
| Loss of the Hetzner project or account                      | ❌                                            | ❌ same provider              | ❌                             |
| The whole OS, not just the database                         | ❌                                            | ❌ database only              | ✅ this is what they are for   |

**A volume is not a backup, and a backup is not a volume.** The volume ([#460](https://github.com/enorm-labs/event-junkie/issues/460)) means a node rebuild is
routine. It means nothing at all about a `DROP TABLE`.

**Server snapshots are on for production and off for staging, both deliberately.** `enable_backups = true` in
`infra/environments/production/main.tf`, and `false` in staging. Production is applied, so the column above describes what runs rather than what is intended.
Staging stays off because it is the environment whose whole job is to be destroyed and rebuilt.

**Nothing survives losing the Hetzner account**, and that is an accepted risk, not an unnoticed one. Off-provider replication is not in ADR-012's budget and
would bring its own key management. If it ever becomes justified, it is a new decision, not a config change.

## 3. Where it goes

```
event-junkie-backups/          one bucket, fsn1, private
├── staging/                   ← BACKUP_PREFIX, derived from `environment` in cloudinit.tf
│   ├── basebackups_005/
│   └── wal_005/
└── production/
```

**One bucket, two environments, separated by prefix.** The Object Storage subscription is billed per account, so a second bucket buys nothing. The prefix is
derived in `cloudinit.tf` rather than typed anywhere, and that is load-bearing. Staging pointed at production's prefix would delete real backups on its next
retention sweep.

**No versioning on this bucket**, unlike `-tfstate`. Versioning would retain a copy of everything `wal-g` deletes, so the retention window would become
decorative and storage would grow without bound. The bucket gets a plain expiry rule instead (§4).

**S3 credentials are project-scoped, not bucket-scoped** — one key pair reaches the state bucket, the observability bucket and this one. Worth knowing before
assuming the backup bucket is isolated.

## 4. Retention — 30 days, enforced twice

**30 days of point-in-time recovery**, decided 2026-08-18. Long enough that corruption noticed weeks later is still recoverable, and a defensible figure to put
in front of a data subject.

**This is a number the privacy notice has to state** ([#277](https://github.com/enorm-labs/event-junkie/issues/277)), which is why it is enforced in two places
rather than one:

1. **`wal-g delete before FIND_FULL <now − 30d> --confirm`**, in the nightly `walg-basebackup.service`. This is the mechanism that understands backup chains.
2. **A bucket lifecycle rule expiring objects at 35 days.** It is the backstop for whenever the node is down and the sweep does not run.

**The five-day gap is deliberate and must not be closed.** The lifecycle rule does not understand backup chains. Expiring at exactly 30 would let S3 remove a
WAL segment that a still-valid 30-day-old base backup depends on. The result is a chain that lists perfectly and cannot be restored. Give the sweep room to do the
job properly and let the rule catch only what it never reached.

**`FIND_FULL` is not optional either.** `wal-g delete before` without it will remove a base backup a later delta still needs, with the same symptom.

**Changing the number means changing the privacy notice.** Both, in the same breath.

## 5. The credential is not in the configuration, and that has a cost

`wal-g` needs an S3 access key. It would reach the node through `user_data`, which goes into OpenTofu state, and **nothing secret goes into state**. So the
split is:

- **The machine installs the mechanism** — `backups.sh` fetches the binary, writes `/etc/wal-g/wal-g.env`, turns on `archive_mode`, enables the timers.
- **The operator supplies the authority** — `/etc/wal-g/credentials.env`, mode 0640 `root:postgres`, written by hand at CLUSTER_BOOTSTRAP.md §8b.

**The cost is real and worth stating plainly.** A rebuilt node comes back with the timers running and no credential. wal-g is installed, `archive_mode` is on, and
every archive attempt fails. Nothing about the node looks wrong. It is the same shape as the `events` role's password, which already dies with a rebuild.

That is not mitigated by remembering. It is mitigated by §6.

## 6. How you know it is working

`walg-check.timer` runs `walg check` hourly. It deliberately does **not** ask "did the last job error" — it asserts three things and fails if any is false:

| Assertion                               | Catches                                                                                              |
| --------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| A base backup exists                    | Never configured; credential missing; wrong prefix                                                   |
| The newest is younger than **26 hours** | Archiving stopped quietly; the nightly job stopped running                                           |
| `/var/lib/postgresql` is under **85%**  | A stalled `archive_command` filling `pg_wal` — on a 10 GB volume, that eventually stops the database |

Only if all three pass does it ping `$HEALTHCHECK_URL`. That conditionality is the whole point, and it matches the argument PLATFORM_SETUP.md §11 makes about
the site monitor. An unconditional heartbeat proves only that the heartbeat ran.

> **`HEALTHCHECK_URL` is set per environment**, and where it is not, `walg check` now says so on every hourly run rather than passing silently:
>
> ```
> warning: HEALTHCHECK_URL is unset in /etc/wal-g/credentials.env — this check passes into a void.
> ```
>
> That line is [#518](https://github.com/enorm-labs/event-junkie/issues/518)'s smallest and most useful part. A dead-man's switch that is not wired up reports
> exactly what a healthy one does, which is nothing. The two states are therefore indistinguishable from outside, and the mechanism can sit built and pointed
> at nothing indefinitely. **A node whose check prints that warning is not monitored**, however green its timers look. The setup, and the drill that proves the
> notification actually arrives, are [HEALTHCHECKS.md](HEALTHCHECKS.md).

Checking by hand:

```sh
ssh ops@<tunnel-address> 'sudo -u postgres /usr/local/bin/walg check'
# ok: newest 2026-08-18T09:58:28Z, disk 2%

ssh ops@<tunnel-address> "sudo -u postgres psql -x -c 'select archived_count, failed_count, last_archived_wal from pg_stat_archiver'"
# failed_count must be 0. A rising failed_count is the earliest warning there is.
```

## 7. What it costs

|                          |                                                                                                                                                    |
| ------------------------ | -------------------------------------------------------------------------------------------------------------------------------------------------- |
| Object Storage           | €4.99/month base, 1 TB storage and 1 TB egress included — shared with `-tfstate` and `-o2`, so the marginal cost of backups is **zero** until 1 TB |
| Actual usage             | 12.2 MiB for staging on 2026-08-18: two base backups and 8 WAL segments, brotli-compressed                                                         |
| Hetzner server snapshots | 20% of server price, production only                                                                                                               |
| PGDATA volume            | ~€0.44/month, 10 GB                                                                                                                                |

**Storage is not the constraint here and will not become one soon.** The constraint is the 10 GB volume, which is what `walg check`'s 85% assertion watches.
`pg_wal` filling up is a far more likely failure than the bucket filling up.

## 8. Keeping `wal-g` current

**A version pinned as a plain string belongs to no ecosystem.** Dependabot covers `gradle`, `npm`, `docker`, `github-actions` and `opentofu`. A tool version in
an OpenTofu variable default is none of those, so it rots silently. `k3s_version` next to it has the same shape. That was this repository's blind spot until
[#1068](https://github.com/enorm-labs/event-junkie/issues/1068).

The pin lives in `infra/modules/environment/variables.tf`:

```hcl
variable "walg_version"   { default = "<release tag>" }
variable "walg_checksums" { default = { amd64 = "<sha256>", arm64 = "<sha256>" } }
```

**What watches it: [`node-pin-reminder.yml`](../../.github/workflows/node-pin-reminder.yml).** Weekly, it opens an assigned issue when either pin falls behind
upstream. It opens no pull request, and [ADR-024](../adr/ADR-024_DEPENDENCY_UPDATE_BOUNDARY.md) § _Why the node pins get a reminder_ says why. Ask the same
question by hand at any time:

```sh
scripts/upstream-node-pins.sh
```

It prints both pins against upstream, exits 1 when either is behind, and computes the two replacement checksums.

**Two things make a `wal-g` bump different from every other pin in that table.**

**It carries checksums, so the bump is not a one-line edit.** Both architectures are pinned — production is ARM, staging is x86 — and both must be refreshed
together, from the release's own `.sha256` files:

```sh
V=v3.0.9
for a in amd64 aarch64; do
  printf '%-8s %s\n' "$a" "$(curl -fsSL "https://github.com/wal-g/wal-g/releases/download/$V/wal-g-pg-24.04-$a.tar.gz.sha256" | cut -d' ' -f1)"
done
```

The `aarch64` asset name maps to the `arm64` key in `walg_checksums`. The script picks by `dpkg --print-architecture`. Pinning the checksum in Terraform rather
than fetching it beside the tarball is the point. A checksum fetched from the host that served the file verifies only that the download completed.

**It changes `user_data`, which replaces the node.** This is the one that matters. Bumping Trivy edits a workflow. Bumping `wal-g` plans a rebuild of every node that
runs PostgreSQL, production included. So:

- **Do not take it because it is available.** The reminder says a newer release exists, and that is all it says. Bump for a reason — a fix you need, an
  advisory — and take the rebuild deliberately. Keep `docs/ops/CLUSTER_BOOTSTRAP.md` § _Rebuilding a node_ open while you do.
- **Or install ahead of the rebuild.** `backups.sh` is idempotent and only downloads when the installed version differs. So you can bump the variable, run the
  script by hand on the node, and let the next natural rebuild find the work already done. The plan still shows a replacement, which is honest, because
  `user_data` genuinely differs.
- **Never let the pin drift from what is installed.** A node rebuilt six months later would silently downgrade to whatever the variable still says.

**The floor on staleness is lower than it looks.** `wal-g` is on the recovery path, not the request path. Nothing about a stale version shows up in monitoring,
and the moment you find out is the moment you are already restoring. Re-read this section when the quarterly drill comes round
([#519](https://github.com/enorm-labs/event-junkie/issues/519)). That is the natural cadence for it.

## 9. The drill

**A backup nobody ever restored is a belief about a backup.** The procedure is [RESTORE_RUNBOOK.md](RESTORE_RUNBOOK.md). Running it against a scratch cluster
on a schedule is what keeps it true.

**Owner: @enorm. Quarterly**, and additionally whenever `backups.sh`, `postgres.sh` or the PostgreSQL major version changes.

**What nags you: [`.github/workflows/restore-drill-reminder.yml`](../../.github/workflows/restore-drill-reminder.yml)** ([#519](https://github.com/enorm-labs/event-junkie/issues/519)).
It opens the drill as an assigned issue on the first of January, April, July and October. It opens one again on any push to `main` that touches `backups.sh`
or `postgres.sh`. A change to either means the last run proved a version that no longer runs. A skipped quarter is therefore an open issue on the board rather
than nothing at all. That is why it is an issue and not a calendar entry. It is idempotent. A dispatch, a re-run and a late schedule all converge on
one issue, and a _closed_ one is never reopened.

**One gap, still open: the issue does not reliably reach the project board.** `GITHUB_TOKEN` cannot write to an organisation project, so this workflow cannot
place the card itself. The board's `Auto-add to project` workflow is meant to cover that. It is enabled, and it still misses issues. Six of 151
open issues sat off the board when #1092 measured it. Two of the six are this workflow's own, #561 and #862. Enabling the setting was recorded here as the
fix, and that was wrong.

**So check the card after a run.** The fallback is one command:

```sh
scripts/issue-board.sh status <n> Ready
```

[#1092](https://github.com/enorm-labs/event-junkie/issues/1092) carries the gap, including whether anything can be done about it from this side.

**The PostgreSQL major version is the one trigger that stays a note rather than a gate.** It lives in `var.postgres_version`, in a variables file that moves for
a dozen unrelated reasons. A path filter there would open a drill issue on every unrelated edit, and teach everyone to close them unread.

**Each run records its timings in its own issue first**, in the table the workflow puts there, and then overwrites the table below. That ordering matters: the
numbers exist somewhere durable before anyone has to remember to update a document.

### Recorded runs

**2026-08-18 — staging — passed, both halves.**

A base backup taken at 09:58:26 was restored from the bucket alone into a scratch cluster and replayed forward. 3,310 events, 3,953 artists and 86 venues came
back exactly, **including a marker row written at 09:58:43, after the base backup was taken**. That marker is what proves WAL archiving rather than file
copying. Then `public.restore_drill` was dropped on the live database and recovered by PITR to a timestamp before the drop. The log said `recovery stopping before
commit of transaction 1914`: table back, live database still without it.

| Step                               | Time                          |
| ---------------------------------- | ----------------------------- |
| Base backup                        | 3.5 s                         |
| `backup-fetch`                     | 10 s                          |
| Replay and promote                 | ~2 s                          |
| **Restore to serving, end to end** | **≈ 12 s** on a 39 MB cluster |

**None of this extrapolates linearly, and an RTO derived from 39 MB is not an RTO.** Re-measure every run and overwrite the table above. The recorded figure then always
reflects the database's current size, rather than the day it was first small.

## 10. Known gaps, named rather than hidden

- **No alerting yet.** §6 — [#518](https://github.com/enorm-labs/event-junkie/issues/518).
- **The drill recurs, but only one has ever run.** §9 — the reminder workflow exists and is idempotent. What it cannot prove is that a quarter's issue gets
  worked rather than closed. That is what the open-issue-on-the-board visibility is for.
- **Production runs all of this.** The gap that used to sit here is closed. `walg check` passes on its database node. Base backups run nightly on the timer,
  not by hand. The dead-man's switch points at a URL that fired in a drill.
- **The cloud-init delivery path is proven, on production.** This was a gap because `backups.sh` reached the staging node by hand rather than through
  `user_data`. Production booted with the script in `user_data`, and takes nightly backups that nobody installed. The gap closed quietly, which is why it stays
  written down rather than deleted.
- **Nobody ever performed a full in-place restore**, only restores into a scratch cluster. RESTORE_RUNBOOK.md §6 says so at the point where it matters.
- **Nothing survives losing the Hetzner account.** §2.

---

**See also** — [RESTORE_RUNBOOK.md](RESTORE_RUNBOOK.md) · [CLUSTER_BOOTSTRAP.md](CLUSTER_BOOTSTRAP.md) §8b ·
[ADR-012](../adr/ADR-012_CLOUD_PLATFORM.md) · [infra/AGENTS.md](../../infra/AGENTS.md) § Backups ·
[wal-g PostgreSQL documentation](https://wal-g.readthedocs.io/PostgreSQL/) — every `wal-g` command and variable used here
