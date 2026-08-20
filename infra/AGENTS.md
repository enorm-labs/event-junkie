# AGENTS.md — `infra/`

OpenTofu for the Hetzner platform. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under `infra/`. Read
[`README.md`](README.md) next to it — that one is written for a human at a terminal, this one for an agent about to change something.

## The one rule that matters

**Never run `tofu plan`, `tofu apply`, `tofu destroy`, or `tofu import` on your own initiative.** They reach real infrastructure, they spend money, and two of
them change things people depend on. If a task appears to require one, stop and say so — do not go looking for a token.

**On an explicit, specific instruction — "apply staging", "destroy the staging stack" — you may.** The rule is against acting unasked, not against being
useful. Two conditions, though, and they are not optional:

- **Show the plan first and check it against what you expect.** `tofu plan -destroy` before a destroy, `plan` before an apply. State the resource count you
  expect _before_ running it, and stop if it differs. A destroy that touches `bootstrap/` or names a DNS zone is wrong no matter who asked for it.
- **Never widen the instruction.** "Destroy staging" is not permission to destroy production, and an instruction given once does not carry to the next
  environment or the next session.

Everything below is safe and needs no credentials:

```sh
tofu fmt -recursive -check -diff infra
tofu -chdir=infra/<stack> init -backend=false      # -backend=false is not optional
tofu -chdir=infra/<stack> validate
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

`<stack>` is one of `bootstrap`, `environments/production`, `environments/staging`. Run all three: they share a module, so a change to it can break one and not
the others. CI runs exactly these commands in `.github/workflows/validate-infra.yml`.

**`validate` does not evaluate variable `validation` blocks either** (measured 2026-08-20). A `default` that breaks its own rule — a bucket name of
`Bad_Name-` against a regex that forbids it — passes `validate` cleanly. The rules still fire, but at plan time, which is the one command nobody may run
unasked. **So a `validation` block is documentation until somebody applies the stack**, and it cannot be treated as a gate CI enforces: `validate-infra.yml`
will go green on a value the variable itself rejects.

**`validate` does not render `templatefile`.** A change to `cloud-init/node.yaml.tftpl` or to any of the `.sh` files can pass `validate` and still produce
cloud-init that does not parse. To check it, render the template with sample data in a scratch directory and parse the result as YAML — the scripts should
round-trip byte-identically through `indent()`. That check has caught real breakage; do not skip it after touching the templating.

## What state this code is in

**`bootstrap/` is applied and real, as of 2026-08-10.** Both DNS zones and their eight records exist on Hetzner and serve correctly; the SSH key is imported
and managed. The S3 backend on Hetzner's Ceph works, including through a partial failure — that was the design's largest unknown and it is now closed.

**`environments/staging` is applied and real, as of 2026-08-13.** One `cpx22` in `nbg1` — x86, not ARM, and that is forced rather than chosen; `main.tf`
explains it. The firewall rules, the k3s flags, WireGuard and the PGDG install have now executed on a real machine and worked on the first boot: k3s `Ready`,
Traefik up, PostgreSQL listening on the private address, tunnel established with the declared peer.

> **The config and the running node disagree right now (2026-08-20).** `main.tf` declares `cx33` — 4 vCPU / 8 GB, and cheaper than the `cpx22` it replaces —
> after the node ran out of memory under the observability stack (#271). **It has not been applied**, so a `tofu plan` on staging will show a pending
> `server_type` change. That is expected, not drift to undo. Applying it is an in-place resize (both x86, both 80 GB disk) and reboots the node.

**The PGDATA volume is applied and proven on staging, as of 2026-08-17 (#460).** The node was replaced and the database came back: a sentinel row written at
20:11:27 was read back on a machine that booted at 20:14:41, and every table matched a dump taken beforehand exactly — zero rows lost. `postgres.sh` logged
`adopting the existing cluster on the volume`, and `hcloud_volume.postgres` did not appear in the plan at all, which is the check that matters. A subsequent
reboot confirmed the fstab entry and the `RequiresMountsFor` drop-in hold when the script does not run. Production still has no volume, because production has
never been applied.

**One thing it is still fair to call unproven**, so do not describe it as verified: the destroy/apply cycle has not been run. The 2026-08-17 rebuild was a server
_replacement_, which is a different thing and does not tick #424's box — a `destroy` would take the volume with it, which is exactly what a replacement does not.

**Staging was rebuilt from scratch on 2026-08-17 and is fully back**: `admin_cidrs` is `[]` again, Flux is reconciling, cert-manager has issued, and the
workloads serve the database that survived the node. Two things about that bring-up are worth carrying forward:

- **The database survived; its credential did not.** The `events` role came through on the volume, but the password lived only in the `events-db` Secret, which
  died with the cluster — and a SCRAM hash is not reversible. So a rebuild needs `ALTER ROLE events PASSWORD …` and a fresh Secret, not `CREATE ROLE`. §8 of
  `docs/ops/CLUSTER_BOOTSTRAP.md` reads as though the database step is all-or-nothing; after a rebuild it is half redundant and half mandatory.
- **The `hetzner` Secret now holds the same token this stack authenticates with**, chosen deliberately on 2026-08-17 over minting a second one. Hetzner tokens
  are project-scoped with no finer grain, so it is the same power either way — but revoking that token now breaks `tofu apply` _and_ DNS-01 together, which is
  the cost of the choice and the thing to remember when rotating.

**`environments/production` has never been applied.** No server, network, firewall or Primary IP described there exists, and `cax21` cannot currently be bought
anywhere in `eu-central`. "Declared" is still the accurate word for that half.

## Layout, and why it is not by environment

```
bootstrap/            DNS zones · SSH keys        — long-lived, outside every destroy
modules/environment/  servers · network · firewall · PGDATA volume · cloud-init
environments/
  production/         CAX21 k3s + CAX11 PostgreSQL · public · address records
  staging/            one CAX11, all-in-one · not on the public internet
```

The split is **by lifetime, not by environment**, and it is load-bearing. `tofu destroy` on an environment is meant to be routine; a DNS zone caught in that
blast radius is not. Delegation would survive — Hetzner's nameservers are fixed — but DNSSEC would not: a re-created zone has a new key, the DS record at INWX
stops matching, and the domain becomes _unresolvable_ rather than merely wrong.

**So: never move a `hcloud_zone` into an environment stack**, and never manage the zone from anywhere but `bootstrap/`. Environments read it with
`data "hcloud_zone"` and own only their own address records.

## Conventions

Beyond `tofu fmt`, this follows [terraform-best-practices.com](https://www.terraform-best-practices.com/):

- **`_` in Terraform identifiers, `-` in values** that a human or a cloud API sees (`"${var.environment}-k3s"`).
- **Never repeat the resource type in the resource name.** `hcloud_zone_rrset "defaults"`, not `"zone_defaults"`.
- **`count` / `for_each` first in the block**, followed by a blank line.
- **`labels` last among real arguments**, then a blank line, then blocks, `depends_on`, `lifecycle`.
- **Plural variable names for `list`/`map` types**; singular resource names even when `for_each` makes several.
- **Variable block order**: `description`, `type`, `default`, `nullable`, `validation`. Every variable has a description and `nullable = false` unless `null`
  carries meaning — it does for exactly one, `postgres_server_type`, where `null` means "co-locate PostgreSQL on the k3s node".
- **Every output has a description**, including the thin pass-through outputs in the environment stacks.
- **Prefer a boolean in a `count` condition** over `length(...)`.

Two deliberate deviations, so nobody "fixes" them: single resources are named `main` rather than `this`, because `main` reads better in a config this small; and
outputs use short names (`k3s_ipv4`) rather than the book's `{name}_{type}_{attribute}`, which is a convention for public registry modules and pure noise here.

## Comments

Comments explain **why**, and specifically why an obvious alternative was not taken — `firewall.tf` opens on why Hetzner firewalls cannot secure the private
network, `servers.tf` on why Primary IPs are separate resources. Match that. Do not add comments that restate the HCL.

Cross-references point at `docs/ops/PLATFORM_SETUP.md` sections (`§4a`, `§8a`) and ADR numbers. Keep them; they are how a reader gets from a line of config to the
argument behind it. If you contradict one of those documents, change the document too, or say plainly that you have not.

## Things that will bite

- **`user_data` forces replacement.** Any edit under `cloud-init/` rebuilds the node, production included. It is also capped at **32 KiB**, and since #270 that
  is no longer a comfortable margin. Measured on this tree:

    | Node                                 | Rendered | Of the cap | Scripts                                   |
    | ------------------------------------ | -------- | ---------- | ----------------------------------------- |
    | k3s, co-located database (staging)   | 29.6 KiB | **92%**    | harden, wireguard, k3s, postgres, backups |
    | PostgreSQL, dedicated (production)   | 22.9 KiB | 71%        | harden, postgres, backups                 |
    | k3s, dedicated database (production) | 12.0 KiB | 37%        | harden, wireguard, k3s                    |

    **The co-located node is the binding constraint and it is nearly full.** `backups.sh` is deliberately under-commented for that reason, and `postgres.sh` and
    `backups.sh` are no longer shipped to a k3s node that has a database next door — that conditional in `cloudinit.tf` is what buys production its headroom, and
    removing it would take the production k3s node from 37% to 91% for two files nothing on it runs. **Measure after any edit under `cloud-init/`**, with the
    render check described above; do not estimate.

- **`server_type` cannot cross architectures, and `tofu plan` will not warn you.** Within one architecture it is an in-place resize; between `cpx*` (x86) and
  `cax*` (ARM) Hetzner refuses — [their FAQ](https://docs.hetzner.com/cloud/servers/faq/) lists rescale alongside snapshots and ISOs as places where "it is not
  possible to work with two different architecture types". The plan renders a tidy in-place update and the **apply** fails against the API partway through. So
  an architecture change is a _rebuild_, not a variable change: see [docs/ops/CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md) §Rebuilding a node. Staging is on
  `cpx22` only because ARM could not be bought (#424), so this is a live concern rather than a hypothetical.
- **Rebuilding a node keeps its database; destroying an environment does not.** `PGDATA` is on an `hcloud_volume` mounted at `/var/lib/postgresql` (#460), and
  the volume is declared standalone — `location`, never `server_id` — so nothing about it references a server and no server edit can plan to replace it.
  Replacing the node therefore leaves the data alone, and `postgres.sh` adopts the cluster already on the volume. **`tofu destroy` still takes it**, because
  `delete_protection` does not stop OpenTofu (see below). Off-server backups are `backups.sh` — see § Backups, and note that they are declared but not yet
  proven by a restore.
- **`postgres.sh` contains no `mkfs`, and must not grow one.** The volume is formatted once by the provider at creation (`format = "ext4"` in `volume.tf`).
  That is deliberate: the script runs on every boot against a volume that already holds a cluster, so the one genuinely destructive command is kept out of the
  file rather than wrapped in a condition somebody can get wrong later. Its seed step copies only into a volume with no cluster on it, and a cluster of an
  unexpected major version aborts the boot instead of being worked around.
- **Volumes are location-bound, like the Primary IPs.** Moving an environment to another location means dealing with the volume — and the data on it — first.
  `location` on the volume does not migrate anything.
- **`delete_protection` does not stop OpenTofu** — the provider lifts its own locks before destroying. Only `lifecycle { prevent_destroy = true }` does, and it
  is used in exactly one place, on the DNS zones. Do not describe any other resource as protected from `destroy`.
- **`ssh_keys` on a server is ignored after creation** (`lifecycle.ignore_changes`), because changing it would rebuild the node and the keys only ever reach
  root, whose login harden.sh disables. Adding an admin key to a _running_ node is a manual step, not a config change.
- **Secrets never enter state.** The WireGuard server keypair is generated on the node at first boot; the Hetzner token and S3 credentials come from the
  environment via direnv (`.envrc.example`). If a change would put a private key, password or token into a variable or an output, it is the wrong change —
  find another way.
- **Never read, print or `cat` `.envrc`, `.env` or `terraform.tfvars`.** The committed `.example` files carry everything needed to understand the shape; the
  real ones are gitignored because of what they may contain. Edit `.envrc.example` if the _set_ of variables changes, never the copy in use.
- **Never echo a credential variable, not even to check it is set.** This one has already gone wrong once, on 2026-08-10, and cost a full rotation of the
  Hetzner token and both S3 keys. `${VAR:+set}${VAR:-EMPTY}` looks like a boolean and is not — the second expansion prints the value whenever the first says
  `set`. The only safe form prints a marker and never the variable:

    ```sh
    direnv exec infra bash -c 'echo "HCLOUD_TOKEN: ${HCLOUD_TOKEN:+set}"'
    ```

    A length is also safe (`${#VAR}`); a default-value expansion never is. If a check needs the value to be _correct_ rather than merely present, use it — pass
    it to a command — do not display it.

- **`admin_cidrs` is a bootstrap value**, not an allowlist to maintain. Its steady state is `[]`. See §8a.
- **Staging has no DNS records on purpose.** It is unreachable from the internet, not merely password-protected, which is also why its TLS needs DNS-01. Adding
  an `A` record for it would quietly undo that.
- **`.tftpl` is not HCL.** `tofu fmt` rejects the extension; the pre-commit hook excludes it for that reason.
- **The cost boundary is the network zone, not the location.** Traffic inside `eu-central` is free, so `fsn1`/`nbg1`/`hel1` are interchangeable and the
  Object Storage buckets — which live in `fsn1` and cannot be moved — do not pin the servers. `region` in `backend.tf` names the _bucket's_ location; never
  change it to follow a server move. Do **not** derive `location` from live capacity: it forces replacement on servers and Primary IPs, so a stock change
  elsewhere would plan a rebuild during an unrelated apply. Decide with `check-capacity.sh`, then edit the one line.
- **Locking is off.** `use_lockfile` is unverified on Hetzner's Ceph, so it sits commented out in all three `backend.tf` files. Do not turn it on speculatively
  — test it, then write the answer into `README.md`.
- **`.terraform.lock.hcl` is committed and Dependabot maintains it** (`opentofu` ecosystem, all four directories grouped into one PR). Do not delete a lock
  file, and do not hand-edit one — regenerate with `tofu providers lock -platform=linux_amd64 -platform=linux_arm64 -platform=darwin_arm64` so CI, the ARM
  nodes and an arm64 laptop all stay covered.

- **A green CI run on a provider bump means the configuration still parses, and nothing more.** `validate-infra.yml` runs `init -backend=false` and `validate`,
  which reaches no API, renders no `templatefile`, and does not evaluate variable `validation` blocks. **None of the three gates in this repository can tell you
  a new provider version still works against Hetzner.** So review an `opentofu` Dependabot PR on this basis rather than on its checks:

    1. **Read the lock diff.** Not every provider is signed — `aminueza/minio` is not, and `init` says so: _"Signature validation was skipped due to the registry
       not containing GPG keys for this provider"_. Each bump is therefore another trust-on-first-use download, and the recorded hashes are the only control.
       A lock diff that changes hashes without changing the version is the one to stop on.
    2. **`tofu -chdir=bootstrap plan` and expect _no changes_.** A version bump against an unmodified configuration should be a no-op. **A diff on a
       configuration nobody edited is the finding** — it means the provider now reads or writes something differently, and that is exactly what a minor release
       is allowed to do and CI cannot see. This needs credentials and is a deliberate act; see the rule at the top of this file.
    3. **`aminueza/minio` carries a specific risk the others do not.** It is a MinIO provider pointed at Hetzner's Ceph, which is why `s3_compat_mode` is set at
       all — features it expects are not all implemented there. Hetzner is one of its tested backends, so this is not a gamble, but the compatibility surface is
       the thing a minor release can move, and step 2 is the only place it would show.

## Backups

`backups.sh` is commented far more thinly than anything else here, because it is rendered into a `user_data` that is 92% full. The operational picture — what
each layer survives, retention, costs, how `wal-g` is kept current — is [docs/ops/BACKUPS.md](../docs/ops/BACKUPS.md), and restoring is
[docs/ops/RESTORE_RUNBOOK.md](../docs/ops/RESTORE_RUNBOOK.md). What follows is what an agent about to change `backups.sh` needs, and nothing else.

**The credential is not in this configuration and must not be put there.** wal-g needs an S3 access key and secret; they would reach the node through
`user_data`, which is state. So the split is: the machine installs the mechanism, the operator writes `/etc/wal-g/credentials.env` by hand
(`docs/ops/CLUSTER_BOOTSTRAP.md` §8b). The honest cost is that **a rebuilt node comes back with the timers and no credential** — the same shape as the `events`
role's password, which already dies with a rebuild. That is not mitigated by care; it is mitigated by `walg check`, below.

**`walg check` is the point, not the backups themselves.** A backup job that exits 0 having uploaded nothing is the failure mode this whole issue exists to
catch, so success is defined as _a base backup exists and is younger than 26 hours_, not _the last run did not error_. Only then does it ping healthchecks.io,
for the reason `PLATFORM_SETUP.md` §11 gives about the site monitor: an unconditional heartbeat proves only that the heartbeat ran. It also asserts
`/var/lib/postgresql` is under 85% full, because a stalled `archive_command` does not merely stop backups — it fills `pg_wal`, and on a 10 GB volume that stops
the database.

**`FIND_FULL` in the retention sweep is not optional.** `wal-g delete before <time>` without it will remove a base backup that a later delta still depends on,
leaving a chain that lists perfectly and cannot be restored.

**Retention is enforced twice, and that is deliberate.** The nightly sweep only runs while the node is healthy, and `backup_retention_days` is a number the
privacy notice has to state (#277) — so a lifecycle rule on the bucket backs it up, and the window cannot quietly become "forever" because a machine was down.
Changing the number means changing the notice.

**One bucket, two environments, separated by a prefix** derived from `environment` in `cloudinit.tf` rather than typed anywhere. It is load-bearing: staging
pointed at production's prefix would delete real backups on its next sweep.

**The binary comes from GitHub, and github.com publishes no AAAA record** (checked 2026-08-18). A node with no public IPv4 cannot install wal-g, which is why
production sets `postgres_public_ipv4 = true` and why `backups.sh` stops the boot rather than coming up without backups. `apt.postgresql.org` _does_ answer on
IPv6, so the older worry in `PLATFORM_SETUP.md` §1 resolves the other way.

**A backup nobody has restored is a belief about a backup.** The drill is `docs/ops/RESTORE_RUNBOOK.md` §4 and §5, it restores into a scratch cluster on port 5433
and never into live `PGDATA`, and it is not optional before go-live.

**Changing `backups.sh` or `postgres.sh` now opens a drill issue by itself** — `.github/workflows/restore-drill-reminder.yml` watches both paths on `main`, so
this is a gate rather than the note it used to be. The quarterly reminder comes from the same workflow. A **PostgreSQL major version** bump is the one that is
still only a note: `var.postgres_version` lives in a file that moves for unrelated reasons, so it is on you to run the drill after one.

**It has been run once: 2026-08-18, staging, both halves passed.** 3,310 events and 3,953 artists came back from the bucket alone, including a marker row
written _after_ the base backup was taken — which is what proves WAL archiving rather than file copying — and a PITR restore recovered a table dropped
afterwards. **Restore to serving: ~12 seconds on a 39 MB cluster.** That number does not extrapolate; re-measure when the database is meaningfully larger.

**What is still unproven, so do not describe it as verified: the cloud-init delivery path.** `backups.sh` was installed and run by hand on the live staging node
rather than through a node replacement, deliberately — the alternative takes k3s, Flux and both secrets with it, and none of that was needed to prove a restore.
So the script is proven; `user_data` carrying it to a fresh node is not, and the first real rebuild is what will settle that.

## If the PostgreSQL node's IPv6-only egress fails

The fallback is a NAT gateway, and the reference is Hetzner's
[Private Network with NAT Gateway and Load Balancer using OpenTofu](https://community.hetzner.com/tutorials/private-network-nat-lb-hetzner-opentofu/). Read it
for the mechanism, not as a template — the cheaper fix is still `postgres_public_ipv4 = true`, and a NAT gateway is only worth building if keeping the node
without a public address matters more than the ~€0.50/month.

The mechanism is one `hcloud_network_route` (`destination = "0.0.0.0/0"`, `gateway` = the k3s node's private address) plus `MASQUERADE` on the k3s node. No
separate NAT server: the k3s node already has both a public IPv4 and a private address, and `wireguard.sh` already enables IP forwarding.

**Four things in that tutorial must not be copied here:**

- **Its VPC is `10.42.0.0/16`, which is k3s's default pod CIDR.** Ours are `10.0.0.0/16` and `10.1.0.0/16` for exactly this reason. Copying its CIDR would
  overlap the cluster network with the private network, and the symptom would be intermittent pod-to-database failures, not an obvious error.
- It uses `network_id` in the server `network` block **while having two subnets** — the case the provider docs call unpredictable. Use `subnet_id`.
- It omits `alias_ips = []`, so it detaches and reattaches the network on every apply.
- Its Load Balancer half does not apply at all: k3s's bundled ServiceLB binds the node IP, which is why PLATFORM_SETUP.md §1 does not order one.

## Two gaps, named rather than hidden

**No `plan` in CI, and therefore no drift detection.** Both need a credential, and §4 of PLATFORM_SETUP.md says nothing outside the cluster holds one. That is a
deliberate trade, not an oversight: the cost is that drift and plan review are manual, and that cost was accepted in exchange for CI holding no key to the
infrastructure. If this ever changes, it needs an ADR, not a workflow edit.

**No automated tests.** OpenTofu supports `.tftest.hcl`, but the meaningful assertions here are all about a running machine, and the unit-testable parts are
thin. The rendering check described above is the substitute.
