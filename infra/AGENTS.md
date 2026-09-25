# AGENTS.md — `infra/`

OpenTofu for the Hetzner platform. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under `infra/`.
[`README.md`](README.md) next to it is written for a human at a terminal — layout, first apply, the tunnel, what surprises a newcomer. This one is for an agent
about to change something, and repeats none of it.

## The one rule that matters

**Never run `tofu plan`, `tofu apply`, `tofu destroy`, or `tofu import` on your own initiative.** They reach real infrastructure, they spend money, and two of
them change things people depend on. If a task appears to require one, stop and say so — do not go looking for a token.

**On an explicit, specific instruction — "apply staging", "destroy the staging stack" — you may**, under two conditions:

- **Show the plan first and check it against what you expect.** `tofu plan -destroy` before a destroy, `plan` before an apply. State the resource count you
  expect _before_ running it, and stop if it differs. A destroy that touches `bootstrap/` or names a DNS zone is wrong no matter who asked for it.
- **Never widen the instruction.** "Destroy staging" is not permission to destroy production, and an instruction given once does not carry to the next
  environment or the next session.

Everything below is safe, needs no credentials, and is what `validate-infra.yml` runs:

```sh
tofu fmt -recursive -check -diff infra
export TF_DATA_DIR="$(mktemp -d)"                  # not optional on a checkout you have used
tofu -chdir=infra/<stack> init -backend=false      # -backend=false is not optional either
tofu -chdir=infra/<stack> validate
unset TF_DATA_DIR
shellcheck -x infra/modules/environment/cloud-init/*.sh
python3 infra/check_user_data.py                   # after any edit under cloud-init/
```

`<stack>` is `bootstrap`, `environments/production` or `environments/staging`; run all three, they share a module.

- **`init -backend=false` still reads `.terraform/terraform.tfstate`**, the record an earlier credentialed `init` left, and reaches the state bucket before it
  honours the flag. `-reconfigure` does not help; a scratch `TF_DATA_DIR` does. CI never meets this because a fresh checkout has no `.terraform`.
- **`-chdir` works for these because none needs a credential.** direnv loads `.envrc` on _entering_ `infra/`, and `-chdir` does not move the shell, so a
  command that reaches the backend has to be `cd infra/environments/production && tofu plan` or `direnv exec infra tofu -chdir=… plan`. The failure is
  `api error InvalidAccessKeyId: UnknownError` — **no key, not a wrong one**, and the stale-`.terraform` case above prints the identical message. Check which
  before suspecting the key.
- **`validate` does not evaluate variable `validation` blocks** — a `default` that breaks its own rule passes, and the rule fires at plan time. A `validation`
  block is documentation until somebody applies the stack; CI goes green on a value the variable rejects.
- **`validate` does not render `templatefile`.** `check_user_data.py` is the check: it renders both roles, parses the result with `yq`, proves each gzipped
  script round-trips, and fails at 30 KiB against Hetzner's 32 KiB `user_data` cap (#1482 — the scripts passed the cap with everything green). Run it; do not
  estimate.

## Looking a provider or module up

**Use the `opentofu` MCP server** ([`.mcp.json`](../.mcp.json); hosted at `https://mcp.opentofu.org/mcp`, no key, one approval per person), not memory and not a
web search. It answers registry search, provider and module details, resource and data-source docs — and it answers from `registry.opentofu.org`, which is
what every `.terraform.lock.hcl` here pins. A Terraform-registry answer can describe a version never published there. The Terraform MCP plugin was removed for
that reason and because it wanted a `TFE_TOKEN` for a Terraform Cloud this project does not have; re-adding it needs an argument, not the observation that
the two registries usually agree.

## What state this code is in

All three stacks are applied and live. `bootstrap/` holds both DNS zones, the SSH key and the S3 backend on Hetzner's Ceph. Staging is one `cx33` in `nbg1`,
all-in-one, with no DNS records. Production is a `cx33` k3s node and a `cx23` PostgreSQL node, x86 because `cax*` cannot be bought in `eu-central`. Both have
a PGDATA volume, and a node replacement has been proven to bring the database back with zero rows lost (#460). **Production is public**: `publish_dns` is
`true`, and setting it to `false` takes the site dark — [CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md) §12 first.

> **`user_data` has drifted on staging and on both production nodes, and an apply REBUILDS THEM.** `servers.tf` forces replacement on it, and `cloud-init/`
> has changed since the last apply. Any apply made for an unrelated reason rebuilds the nodes too, and looks like the unrelated change's doing. The volume,
> the Primary IPs, the network and the firewall survive; the k3s cluster does not.
>
> **There is no targeted way out.** `hcloud_volume_attachment.postgres` names both servers in one ternary, so `-target` pulls the k3s node in. The address
> records are the exception (#883): they read the Primary IPs, so `-target=hcloud_zone_rrset.address` — the go-live flip — touches DNS alone. Fixes reach the
> running nodes by hand, [CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md) § Applying a `cloud-init` fix without rebuilding.

Two things a rebuild teaches: **the database survives, its credential does not** — the `events` role's password lived only in a Secret that dies with the
cluster, so a rebuild needs `ALTER ROLE events PASSWORD …`, not `CREATE ROLE`; and **the `hetzner` Secret holds the same token this stack authenticates
with**, so revoking it breaks `tofu apply` and DNS-01 together.

**Still unproven, so do not describe as verified:** the destroy/apply cycle — a replacement is not a destroy, and a destroy takes the volume. The
`user_data` delivery of `backups.sh` is proven: production booted with it and takes nightly backups nobody installed (BACKUPS.md).

## Layout and conventions

`bootstrap/` (DNS zones, SSH keys — long-lived) · `modules/environment/` (servers, network, firewall, volume, cloud-init) · `environments/{production,staging}`.
The split is **by lifetime, not by environment**: a DNS zone caught in a routine `destroy` gets a new DNSSEC key, the DS record at INWX stops matching, and the
domain becomes _unresolvable_. **Never move a `hcloud_zone` into an environment stack**; environments read it with `data "hcloud_zone"`.

Beyond `tofu fmt`, [terraform-best-practices.com](https://www.terraform-best-practices.com/): `_` in identifiers, `-` in values a cloud API sees; never repeat
the resource type in the name (`hcloud_zone_rrset "defaults"`); `count`/`for_each` first, `labels` last among arguments, then blocks, `depends_on`,
`lifecycle`; plural names for lists and maps; variable blocks ordered `description`, `type`, `default`, `nullable`, `validation`, every one described and
`nullable = false` unless `null` carries meaning (only `postgres_server_type`: `null` co-locates PostgreSQL); every output described; a boolean in `count`
over `length(...)`. Two deliberate deviations: single resources are `main`, not `this`, and outputs use short names (`k3s_ipv4`).

Comments explain **why**, and specifically why an obvious alternative was not taken — `firewall.tf` opens on why Hetzner firewalls cannot secure the private
network. Cross-references point at `docs/ops/PLATFORM_SETUP.md` sections and ADR numbers; if you contradict one, change the document too.

## Things that will bite

- **A `k3s_version` bump is not a rebuild.** The plan says replace (`user_data` is force-new), but k3s upgrades in place through the installer the node booted
  with: [K3S_UPGRADE.md](../docs/ops/K3S_UPGRADE.md), bump the pin in the same change. Same shape for `walg_version`, BACKUPS.md §8.
- **`user_data` forces replacement and is capped at 32 KiB.** Every `.sh` travels `gz+b64` through `node.yaml.tftpl`; `check_user_data.py` mirrors the
  template and the two file lists in `cloudinit.tf` and asserts every `.sh` under `cloud-init/` is shipped by some role. `postgres.sh` and `backups.sh` are not
  shipped to a k3s node with a database next door — that conditional keeps production's k3s node smallest.
- **"In-place" is a property of an attribute, never a prediction about an apply.** `server_type` updates in place within an architecture, and the plan still
  replaced the node because `user_data` had drifted. **Read the plan; do not reason from the schema.**
- **`server_type` cannot cross architectures, and `plan` will not warn you.** Between `cpx*`/`cx*` (x86) and `cax*` (ARM) Hetzner refuses at **apply**,
  partway through. An architecture change is a rebuild: CLUSTER_BOOTSTRAP.md § Rebuilding a node.
- **Rebuilding a node keeps its database; destroying an environment does not.** PGDATA is an `hcloud_volume` declared standalone (`location`, never
  `server_id`), so no server edit can plan to replace it and `postgres.sh` adopts the cluster on it. `tofu destroy` still takes it — `delete_protection` does
  not stop OpenTofu; only `lifecycle { prevent_destroy }` does, and only the DNS zones carry it. Volumes are location-bound, like the Primary IPs.
- **`postgres.sh` contains no `mkfs`, and must not grow one.** The provider formats the volume once (`format = "ext4"`); the script runs on every boot against
  a volume that already holds a cluster. Its seed step copies only into an empty volume, and an unexpected major version aborts the boot.
- **`ssh_keys` on a server is ignored after creation** (`ignore_changes`): a change would rebuild the node and the keys only reach root, which `harden.sh`
  disables. Adding an admin key to a running node is a manual step.
- **Secrets never enter state.** The WireGuard keypair is generated on the node; the Hetzner token and S3 keys come from direnv (`.envrc.example`). A change
  that puts a key, password or token into a variable or output is the wrong change.
- **Never read, print or `cat` `.envrc`, `.env` or `terraform.tfvars`**; the `.example` files carry the shape. **Never echo a credential variable, not even to
  check it is set** — `${VAR:+set}${VAR:-EMPTY}` prints the value, and this cost a rotation of the Hetzner token and both S3 keys. The safe forms:
  `direnv exec infra bash -c 'echo "HCLOUD_TOKEN: ${HCLOUD_TOKEN:+set}"'` or `${#VAR}`. To check a value is _correct_, pass it to a command; do not display it.
- **`admin_cidrs` is a bootstrap value**, steady state `[]` (PLATFORM_SETUP.md §8a). **Staging has no DNS records on purpose** — unreachable, not
  password-protected, which is why its TLS needs DNS-01; an `A` record would undo that.
- **`.tftpl` is not HCL**; `tofu fmt` rejects it and the pre-commit hook excludes it.
- **The cost boundary is the network zone.** `fsn1`/`nbg1`/`hel1` are interchangeable inside `eu-central`; the buckets live in `fsn1` and `region` in
  `backend.tf` names the _bucket's_ location — never change it to follow a server move. Never derive `location` from live capacity: it forces replacement on
  servers and Primary IPs. Decide with `check-capacity.sh`, then edit the one line.
- **Locking is off**: `use_lockfile` is unverified on Hetzner's Ceph and sits commented out in every `backend.tf`. Test it before turning it on; write the
  answer into `README.md`.
- **`.terraform.lock.hcl` is committed and Dependabot maintains it** (all four directories in one PR). Never hand-edit or delete one; regenerate with
  `tofu providers lock -platform=linux_amd64 -platform=linux_arm64 -platform=darwin_arm64`.
- **A green CI run on a provider bump means the configuration still parses, and nothing more.** No gate here reaches Hetzner. Review an `opentofu` Dependabot
  PR by: (1) the lock diff — `aminueza/minio` is unsigned, so each bump is trust-on-first-use and a hash change without a version change is the stop; (2)
  `tofu -chdir=bootstrap plan` expecting **no changes** — a diff on an unedited configuration is the finding, and this needs a credential and an instruction;
  (3) `aminueza/minio` points at Hetzner's Ceph through `s3_compat_mode`, and a minor release can move that surface, which only step 2 shows.

## Backups

`backups.sh` is commented thinly because it rides in a `user_data` near its cap. The operational picture is [BACKUPS.md](../docs/ops/BACKUPS.md), restoring
is [RESTORE_RUNBOOK.md](../docs/ops/RESTORE_RUNBOOK.md). What an agent about to change `backups.sh` needs:

- **The credential is not in this configuration and must not be put there** — `user_data` is state. The operator writes `/etc/wal-g/credentials.env` by hand
  (CLUSTER_BOOTSTRAP.md §8b), so **a rebuilt node comes back with the timers and no credential**. `walg check` is the mitigation, not care.
- **`walg check` is the point.** Success is _a base backup exists and is younger than 26 hours_, not _the last run did not error_; only then does it ping
  healthchecks.io. It also asserts `/var/lib/postgresql` is under 85% full, because a stalled `archive_command` fills `pg_wal` and stops the database.
- **`FIND_FULL` in the retention sweep is not optional** — without it `wal-g delete before` removes a base backup a later delta depends on, leaving a chain that
  lists and cannot restore.
- **Retention is enforced twice, deliberately**: the nightly sweep and a bucket lifecycle rule, because `backup_retention_days` is a number the privacy notice
  states (#277). Changing the number means changing the notice.
- **One bucket, two environments, separated by a prefix** derived from `environment` in `cloudinit.tf`, never typed. Staging on production's prefix would
  delete real backups on its next sweep.
- **github.com publishes no AAAA record**, so a node with no public IPv4 cannot install wal-g: that is why production sets `postgres_public_ipv4 = true` and why
  `backups.sh` stops the boot rather than coming up without backups.
- **Changing `backups.sh` or `postgres.sh` opens a drill issue by itself** (`restore-drill-reminder.yml` watches both on `main`). A PostgreSQL major bump does
  not; run the drill yourself after one. The drill (RESTORE_RUNBOOK.md §4–§5) restores into a scratch cluster on port 5433, never live `PGDATA`, and has
  passed on staging, both halves. Restore to serving was ~12 seconds on a 40 MB cluster; re-measure when the database is larger.

## If the PostgreSQL node's IPv6-only egress fails

The cheaper fix is `postgres_public_ipv4 = true` (~€0.50/month). A NAT gateway is one `hcloud_network_route` (`destination = "0.0.0.0/0"`, `gateway` = the
k3s node's private address) plus `MASQUERADE` on the k3s node, which already has a public IPv4 and IP forwarding from `wireguard.sh`. Hetzner's
[tutorial](https://community.hetzner.com/tutorials/private-network-nat-lb-hetzner-opentofu/) has the mechanism; **four things in it must not be copied**: its
VPC is `10.42.0.0/16`, k3s's pod CIDR (ours are `10.0.0.0/16` and `10.1.0.0/16`, and the symptom of overlap is intermittent pod-to-database failure); it uses
`network_id` with two subnets — use `subnet_id`; it omits `alias_ips = []`, so it reattaches the network on every apply; and its Load Balancer half does not
apply, because k3s's ServiceLB binds the node IP.

## Two gaps, named rather than hidden

**No `plan` in CI, and therefore no drift detection.** Both need a credential, and nothing outside the cluster holds one (PLATFORM_SETUP.md §4). A deliberate
trade; changing it needs an ADR, not a workflow edit. **No automated tests.** The meaningful assertions are about a running machine; `check_user_data.py` is the
substitute.
