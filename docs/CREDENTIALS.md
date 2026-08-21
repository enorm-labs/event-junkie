# Credentials — what to keep in KeePass

**No secret values are in this file, and none ever should be.** It is the inventory: what exists, what it unlocks, where the working copy lives today, and what
breaks if the only copy is lost. Use it as the checklist when filling the database, and re-read it after any infrastructure change.

The links are in [`LINKS.md`](LINKS.md). The procedures are in `docs/ops/` — this file points at them rather than repeating them.

## How to read the "only copy?" column

**Yes** means there is no other copy anywhere, so losing it means regenerating the credential and updating everything that consumes it. Those are the entries
where a password manager is not convenience but the recovery story. **No** means it can be re-derived, re-read from somewhere, or recreated in minutes.

---

## 1. Accounts — logins, 2FA seeds and recovery codes

Store the password, the TOTP seed, **and the recovery codes** for each. The recovery codes are the half people skip, and they are what a lost phone costs.

| #   | Account                                  | Unlocks                                                                      | Status                                 | Only copy? |
| --- | ---------------------------------------- | ---------------------------------------------------------------------------- | -------------------------------------- | ---------- |
| 1   | **Hetzner** (`accounts.hetzner.com`)     | Everything. Servers, volumes, firewalls, DNS zones, Object Storage, the AVV  | In use                                 | Yes        |
| 2   | **GitHub** — personal + `enorm-labs` org | The repository, Actions, GHCR packages, Flux's deploy key, branch protection | In use                                 | Yes        |
| 3   | **INWX** (registrar)                     | Domain renewal, nameserver delegation, the DNSSEC DS record                  | In use                                 | Yes        |
| 4   | **healthchecks.io**                      | The dead-man's switch — its checks, its notification channel, its ping URLs  | In use                                 | Yes        |
| 5   | **Postflex**                             | The rented imprint address (§ 5 DDG)                                         | **Not ordered** — go-live blocker      | Yes        |
| 6   | **Signal**, on its own prepaid number    | The alert bridge's identity. Registration state also lives on a PVC          | **Decided, not built**                 | Yes        |
| 7   | **Hetzner Webhosting S** (konsoleH)      | `hello@` and `security@event-junkie.de`. Its own login, not the Cloud one    | **In use** — since 2026-08-21          | Yes        |
| 8   | **OpenObserve** admin login              | Logs, metrics, dashboards, alert rules. Created at first start               | **In use** — staging, since 2026-08-20 | Yes        |

**On the Hetzner account specifically:** it is the single point of total failure here. It holds the infrastructure, the DNS, the backups and the state file.
Treat its 2FA recovery codes with the same care as the age key in §3.

**Prepaid SIM (#6):** keep the number, the PIN, the **PUK** and the top-up schedule together. Signal blocks most VoIP providers for registration, and a lapsed
prepaid number silently ends the alerting path.

---

## 2. API tokens and keys

| #   | Credential                                                                        | Scope / power                                                                                                                                      | Where the working copy lives                                                                                                                                                                  | Only copy?                            |
| --- | --------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| 9   | **Hetzner Cloud API token** (`HCLOUD_TOKEN`)                                      | **Read + write over the whole project.** Servers, networks, firewalls, IPs, DNS                                                                    | macOS Keychain, item `event-junkie-hcloud-token`; loaded by `infra/.envrc`                                                                                                                    | Yes — shown once at creation          |
| 10  | **Object Storage S3 access key**                                                  | **Project-scoped, not bucket-scoped** — one pair reaches `-tfstate`, `-o2` and `-backups` alike. **No longer the one OpenObserve holds — see 10a** | Keychain items `event-junkie-s3-access-key` / `event-junkie-s3-secret-key`, plus `/etc/wal-g/credentials.env` on each DB node                                                                 | Yes                                   |
| 10a | **Object Storage S3 key, OpenObserve's own**                                      | **Identical power to #10 — see the correction below.** What it buys is rotation independence, not reduced reach                                    | Keychain items `event-junkie-o2-access-key` / `event-junkie-o2-secret-key`, the `openobserve-credentials` Secret on staging (`flux-system` **and** `observability`), and the password manager | Yes                                   |
| 11  | **cert-manager Hetzner DNS token**                                                | An hcloud token, **read + write**, for DNS-01. **Staging only** — production uses HTTP-01 and holds none                                           | Kubernetes Secret `hetzner` in namespace `cert-manager`, **hand-made**                                                                                                                        | Yes — deliberately not SOPS-encrypted |
| 12  | **`NVD_API_KEY`**                                                                 | Rate limit on the NVD feed. No access to anything of ours                                                                                          | GitHub Actions repository secret, and `export NVD_API_KEY=…` locally                                                                                                                          | No — request another                  |
| 13  | **GitHub PAT, classic, `write:packages`**                                         | Local `docker push` / `helm push` to GHCR. **Fine-grained tokens do not work here**                                                                | Created on demand; not stored anywhere by the repo                                                                                                                                            | No                                    |
| 14  | **GitHub PAT for `flux bootstrap`**                                               | One-time, from a laptop, to commit Flux's manifests and create its deploy key. **CI never holds it**                                               | Created on demand, then discarded                                                                                                                                                             | No                                    |
| 15  | **`github-dispatch` PAT** (fine-grained) — `event-junkie-staging-github-dispatch` | **`contents: write` on this repository** — can trigger any `repository_dispatch` workflow on `main`                                                | Created 2026-08-19, **expires 2027-08-20**. **Hand-made** Kubernetes Secret in `flux-system`, one per cluster — nothing in the repo creates it ([`ops/SECRETS.md`](ops/SECRETS.md))           | No                                    |
| 15a | **`github-dispatch` PAT** — `event-junkie-production-github-dispatch`             | Same power as #15, on the **production** cluster. Created 2026-08-21                                                                               | Keychain item of that name; **hand-made** Secret in production's `flux-system`. One PAT per cluster by decision, not convenience                                                              | No                                    |

**`secrets.GITHUB_TOKEN` is not on this list and never should be.** Actions mints it per run; `permissions: packages: write` is the whole configuration.

**#15 is the most powerful GitHub credential either cluster holds** — `contents: write` lets it trigger any `repository_dispatch` workflow on `main` — so there
is **one per cluster**, and revoking one does not take the other down. There was briefly a second, weaker `github-status` PAT for commit statuses; it was never
created and [#567](https://github.com/enorm-labs/event-junkie/issues/567) removed the need for it, because a HelmRelease reports a chart version rather than a
commit and no token could have changed that.

**#15 is the only credential here with an expiry date, and that is the one thing about it worth a reminder.** A fine-grained PAT caps out at 366 days, and when
it lapses **nothing on GitHub says so** — the Environments tab simply stops gaining entries, which looks identical to "no deploys happened lately". Flux logs
the rejection inside the cluster and nowhere else.

**So it is not left to memory.** [`credential-expiry-reminder.yml`](../.github/workflows/credential-expiry-reminder.yml) opens an assigned issue 30 days out,
and a differently-titled, louder one if the date passes anyway ([#569](https://github.com/enorm-labs/event-junkie/issues/569)). **The dates live in that
workflow's `CREDENTIALS` table as well as in the row above, and the two must be kept in step** — the same rule the pinned tool versions carry. Adding a
credential means one line there and one row here; rotating one means editing both, which the issue it opens says explicitly. **`event-junkie-staging-github-dispatch` expires 2027-08-20**; the name encodes its cluster because there
will eventually be one per cluster per provider, and the list sorts into pairs that way.

The durable fix is a **GitHub App** rather than a PAT: owned by `enorm-labs` instead of a person, installed on this repository alone with _Contents: write_, and
with no expiry to miss. Flux takes it as `githubAppID` / `githubAppInstallationID` / `githubAppPrivateKey` in the same Secret, so no manifest changes.

**Why #11 is hand-made while everything else moves to SOPS:** encrypting a secret into a _public_ repository publishes its ciphertext permanently, and this is
the one credential where the exposure cost is highest — read+write control of the Hetzner account — and the rebuild-survival benefit is lowest, since it is
staging-only and a two-minute recreation. That trade is argued in [`ops/SECRETS.md`](ops/SECRETS.md).

---

## 3. Cryptographic keys and key files

These are files, not strings. **Attach the file itself to the KeePass entry** rather than pasting its contents.

| #   | Key                              | What it unlocks                                                                                                                       | Path on the laptop                                                                                       | Only copy?                           |
| --- | -------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------ |
| 16  | **SOPS age key pair** ⚠️         | **Every encrypted secret in the repository.** With it, the repository restores everything; without it, the ciphertext in git is noise | `~/.config/sops/age/event-junkie.txt`                                                                    | **Yes — this is the recovery story** |
| 17  | **SSH key for the nodes**        | `ops@` on every server. Root by `sudo` from there                                                                                     | `~/.ssh/id_ed25519_hetzner` (+ its passphrase)                                                           | Yes                                  |
| 18  | **WireGuard client private key** | The tunnel. **Staging has no other way in** — no public 80/443/22/6443                                                                | `~/.wireguard/staging.conf`                                                                              | Yes                                  |
| 18a | **WireGuard key, production**    | Production's tunnel. A separate keypair on a separate subnet (`10.10.0.x` vs staging's `10.10.1.x`) so both can be up at once         | `~/.wireguard/production.conf` + `production.key`                                                        | Yes                                  |
| 19  | **Kubeconfig**                   | Cluster-admin. Only usable through the tunnel                                                                                         | `~/.kube/event-junkie-staging`, `~/.kube/event-junkie-production`, and both merged into `~/.kube/config` | No — re-fetchable over SSH           |

**#16 is the single most important entry in the database.** The public half is committed in `.sops.yaml` and is safe to publish; the private half must never
reach this repository or the cluster's git history. `age-keygen` writes the public key as a comment inside the same file, so **back up the file, not the two
halves separately**. Rotation is: add a second recipient, `sops updatekeys` over every encrypted file, replace the cluster secret, drop the old recipient — two
recipients briefly, so nothing is undecryptable mid-flight.

**#18 has a second half worth storing beside it:** the node's WireGuard _public_ key and endpoint address, which are what you need to rebuild a client config
without going back to the node.

---

## 4. Passwords that live in the cluster or on a node

| #   | Credential                            | What it is                                                                                                             | Source of truth                                                                                                           | Only copy?                                    |
| --- | ------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | --------------------------------------------- |
| 20  | **`events` PostgreSQL role password** | The application's database login                                                                                       | **Both:** SOPS-encrypted in `deploy/clusters/<env>/secrets/events-db.yaml`, restored by Flux. Production since 2026-08-21 | No, once encrypted — but only if #16 survives |
| 21  | **healthchecks.io ping URLs** ⚠️      | One opaque UUID per environment. **Anyone holding one can suppress the alarm**, and the failure it causes is _silence_ | `/etc/wal-g/credentials.env` on the node, and the healthchecks.io dashboard                                               | No — readable from the dashboard              |
| 22  | **`/etc/wal-g/credentials.env`**      | The S3 key (#10) plus `HEALTHCHECK_URL` (#21), mode `0640 root:postgres`                                               | Written by hand. **Not in `user_data`** — `user_data` is state                                                            | No — reconstructible from #10 and #21         |
| 23  | **Flux deploy key**                   | Read access to this repository from the cluster                                                                        | Created by `flux bootstrap`; lives in the cluster and in the repo's deploy keys                                           | No — re-bootstrap                             |

**#10a does not do what this table said it did, and the correction matters more than the entry.** It read: _"created so the observability stack does not hold
#10 — a pod that parses untrusted venue HTML should not carry a credential reaching the OpenTofu state and the database backups"_. That describes least
privilege, and Hetzner does not offer it: **S3 credentials there are project-scoped, exactly as #10 says two rows above.** Tested directly on 2026-08-21 with
#10a's key — objects written to and deleted from both other buckets:

```
o2 key -> event-junkie-backups    write SUCCEEDED
o2 key -> event-junkie-tfstate    write SUCCEEDED
```

So a compromised OpenObserve reaches the state file and the backups whichever key it holds. **What a separate key does buy is rotation independence** — revoking
OpenObserve's credential does not stop OpenTofu or wal-g, and the two show up separately in any access log. That is worth having; it is not a boundary, and
anyone reading the old wording would have believed a boundary existed.

**Found by breaking it.** The staging rebuild on 2026-08-21 recreated `openobserve-credentials` from the Keychain, which held only #10 — #10a lived solely in
the Secret that the rebuild destroyed and in the password manager. It is now in the Keychain under the names above, so the next rebuild cannot repeat it.

**#20 has a trap in both directions.** The git copy is now the source of truth, so bringing up an _existing_ environment means making the database role match
the encrypted value, not generating a fresh one. A role and a Secret that disagree is a `CrashLoopBackOff` whose cause is two files apart.

**#22 is what a node rebuild silently loses.** The volume carries the database through a rebuild; this file does not. A rebuilt node comes back with both timers
enabled, `wal-g` installed, `archive_mode = on`, every archive failing — and no ping. Nothing about it looks wrong. **Paste the same ping URL back rather than
creating a new check**, or the history that makes "late" mean something starts over.

---

## 5. Not credentials, but store them anyway

Small facts that are annoying to re-derive and are needed exactly when something is broken.

- **Node addresses**: the WireGuard tunnel addresses (`10.10.1.1` staging, `10.10.0.1` production), the private networks (`10.1.1.0/24` and `10.0.1.0/24`), and
  each server's public IPv4. Production's database node has a public IPv4 for **egress only** — its firewall admits nothing inbound, so it is reached at
  `10.0.1.20` through the k3s node.
- **The Hetzner project name and Object Storage bucket names** — bucket names are unique Hetzner-wide, so they are not guessable after the fact.
- **The countersigned Hetzner AVV (PDF)**, concluded 2026-08-19. Concluding it and not filing it is the same position as not concluding it, the day somebody asks.
- **The `age` public key** (`age1…`) — it is in `.sops.yaml`, but having it beside the private key means a rebuild does not need the repository first.

---

### What is in the macOS Keychain, and why that is not a backup

`infra/.envrc` loads some of these; the rest are there so a rebuild does not need the password manager at 02:00. **The Keychain is a working copy, not the
record** — it lives on one laptop, is not versioned, and the 2026-08-21 rebuild proved what happens when something exists only in a place that gets destroyed
(see the #10a correction above).

| Keychain item                                | Which entry        | Loaded by                                             |
| -------------------------------------------- | ------------------ | ----------------------------------------------------- |
| `event-junkie-hcloud-token`                  | #9                 | `infra/.envrc`                                        |
| `event-junkie-s3-access-key` / `-secret-key` | #10                | `infra/.envrc`, and `MINIO_*` for the bucket provider |
| `event-junkie-o2-access-key` / `-secret-key` | #10a               | by hand, when rebuilding `openobserve-credentials`    |
| `event-junkie-staging-github-dispatch`       | #15                | by hand                                               |
| `event-junkie-production-github-dispatch`    | #15a               | by hand                                               |
| `event-junkie-staging-healthcheck-url`       | #21                | by hand, into `/etc/wal-g/credentials.env`            |
| `event-junkie-production-healthcheck-url`    | #21                | as above                                              |
| `event-junkie-production-events-db`          | #20                | by hand, and encrypted into git                       |
| `event-junkie-staging-o2-root-password`      | #8                 | by hand, into `openobserve-credentials`               |
| `event-junkie-staging-metrics-password`      | the `metrics` role | by hand, into `postgres-exporter`                     |

**Every one of these belongs in KeePass too.** The two OpenObserve/metrics passwords were regenerated during the 2026-08-21 rebuild rather than recovered — the
old values died with the node's PVC — so if the password manager still holds the pre-rebuild ones, they are stale.

## 6. Suggested KeePass layout

One group per section above maps cleanly onto how these are actually used:

```
event-junkie/
├── Accounts/          Hetzner · GitHub · INWX · healthchecks.io · Postflex · Signal · mail · OpenObserve
├── API tokens/        HCLOUD_TOKEN · S3 key pair · cert-manager DNS token · NVD · the PATs
├── Keys/              age key (file attachment) · SSH key (file) · WireGuard config (file) · kubeconfig
├── Cluster & node/    events role password · ping URLs · wal-g credentials.env
└── Reference/         addresses · bucket names · the AVV PDF · the age public key
```

**Two entries deserve a note in their own description**, because losing them is not recoverable by resetting anything: the **age private key** (#16) and the
**Hetzner account recovery codes** (#1).

---

## 7. What is _not_ here, and why

- **`secrets.GITHUB_TOKEN`** — minted per workflow run.
- **The k3s node token, the Flux deploy key's private half, the WireGuard server key** — all generated on the node or by the tool at bootstrap, and all
  recreated by re-running it. Nothing to store; storing them would just be a stale copy.
- **`admin_cidrs`** — an input, not a secret. It stopped mattering once WireGuard replaced it as the access control.
