# Troubleshooting

Symptom first. Then the one check that tells the causes apart, then the fix, then where the reasoning lives. **Nothing here explains why.** Every entry
links to the runbook that does. Read this when something is wrong and you do not yet know what.

## The short version

Find the line that looks like your screen, and jump.

| You see                                                                           | Go to                                                                                             |
| --------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `wg-quick up` succeeded and nothing answers                                       | [The tunnel is up and nothing answers](#the-tunnel-is-up-and-nothing-answers)                     |
| Every `kubectl` hangs                                                             | [Everything hangs](#everything-hangs)                                                             |
| The tunnel stopped working after a node rebuild                                   | [The tunnel is dead after a rebuild](#the-tunnel-is-dead-after-a-rebuild)                         |
| `Permission denied (publickey)`                                                   | [ssh refuses the key](#ssh-refuses-the-key)                                                       |
| `ops@10.10.0.1: Permission denied`                                                | [The jump host refuses](#the-jump-host-refuses)                                                   |
| `kubectl` fails with `localhost:8080 connection refused`                          | [kubectl fails inside ssh](#kubectl-fails-inside-ssh)                                             |
| `staging.event-junkie.de` does not resolve                                        | [The staging name does not resolve](#the-staging-name-does-not-resolve)                           |
| A certificate warning on staging                                                  | [The certificate warning](#the-certificate-warning)                                               |
| `/actuator/health` or `/events` answers `text/html`                               | [A path answers with HTML that should not](#a-path-answers-with-html-that-should-not)             |
| `/api/admin` answers `404`                                                        | [The admin API answers 404](#the-admin-api-answers-404)                                           |
| A port-forward seeded the wrong database                                          | [The forward landed on the local stack](#the-forward-landed-on-the-local-stack)                   |
| `ej.sh up` says a port is already served                                          | [A port is already served](#a-port-is-already-served)                                             |
| A source is `FAILED` with `stuck in RUNNING for >30 minutes`                      | [A source failed at the minute of a rollout](#a-source-failed-at-the-minute-of-a-rollout)         |
| The run wrote 29 events and the site shows 0                                      | [The source wrote events the site does not show](#the-source-wrote-events-the-site-does-not-show) |
| `flux get all --status-selector ready=false` shows nothing, the release is not up | [The ready filter shows nothing](#the-ready-filter-shows-nothing)                                 |
| A cluster runs an older chart than the newest published                           | [The cluster runs an old chart](#the-cluster-runs-an-old-chart)                                   |
| A chart change merged and production did not move                                 | [Production did not move after a merge](#production-did-not-move-after-a-merge)                   |
| `release.yml` is red at _Scan the images_                                         | [Publishing is blocked](#publishing-is-blocked)                                                   |
| The HelmRelease rolled back                                                       | [The upgrade rolled back](#the-upgrade-rolled-back)                                               |
| `is SOPS encrypted, configuring decryption is required`                           | [The sync cannot decrypt](#the-sync-cannot-decrypt)                                               |
| The OpenObserve page does not load                                                | [OpenObserve does not load](#openobserve-does-not-load)                                           |
| `MemoryTableOverflowError`, collector 503s                                        | [OpenObserve stops ingesting](#openobserve-stops-ingesting)                                       |
| An alert fixed in git keeps firing                                                | [An alert fixed in git keeps firing](#an-alert-fixed-in-git-keeps-firing)                         |
| Dashboard panels are empty                                                        | [Panels are empty](#panels-are-empty)                                                             |
| The OpenObserve console is empty after a rebuild                                  | [The console is empty after a rebuild](#the-console-is-empty-after-a-rebuild)                     |
| `no pg_hba.conf entry for host`                                                   | [PostgreSQL refuses the tunnel address](#postgresql-refuses-the-tunnel-address)                   |
| `password authentication failed for user "events"`                                | [PostgreSQL refuses the password](#postgresql-refuses-the-password)                               |
| `walg check` exits 1, or `walg-basebackup` fails                                  | [walg check fails](#walg-check-fails)                                                             |
| The timers are green and there is no backup                                       | [The timers are green and nothing is backed up](#the-timers-are-green-and-nothing-is-backed-up)   |
| `warning: HEALTHCHECK_URL is unset`                                               | [The check pings nothing](#the-check-pings-nothing)                                               |
| PostgreSQL is `active (running)` and every client is refused                      | [PostgreSQL is running and refuses everyone](#postgresql-is-running-and-refuses-everyone)         |
| The database stops accepting writes                                               | [The database stops accepting writes](#the-database-stops-accepting-writes)                       |
| Something holds port `15432` days later                                           | [A forward holds port 15432](#a-forward-holds-port-15432)                                         |
| `tofu init -backend=false` fails with `InvalidAccessKeyId`                        | [tofu init fails without credentials](#tofu-init-fails-without-credentials)                       |
| A nightly workflow did not run                                                    | [A nightly workflow did not run](#a-nightly-workflow-did-not-run)                                 |
| `npm warn EBADENGINE`                                                             | [npm warns about the engine](#npm-warns-about-the-engine)                                         |
| A Playwright test fails against a fix that is in the file                         | [Playwright tests the wrong checkout](#playwright-tests-the-wrong-checkout)                       |
| `NoClassDefFoundError` in packages the change never touched                       | [Gradle loses classes](#gradle-loses-classes)                                                     |
| `gh` says `API rate limit exceeded` and `gh api rate_limit` shows thousands left  | [gh refuses writes](#gh-refuses-writes)                                                           |

Every check below is read-only. **Nothing in this file writes to a cluster.** A fix that writes says so, and names the runbook that owns it.

---

## The tunnel is up and nothing answers

**Check:** `sudo wg show`. Look for `latest handshake`. The interface and the routes appear whether or not a handshake happened.

**Fix:** No handshake usually means the network blocks outbound UDP/51820, not a broken node. Change networks. After a node rebuild, see
[The tunnel is dead after a rebuild](#the-tunnel-is-dead-after-a-rebuild).

**Why:** [CLUSTER_ACCESS.md §1](CLUSTER_ACCESS.md#1--bring-the-tunnel-up).

## Everything hangs

**Check:** `scripts/ej.sh status`. A tunnel line that says `DOWN`, or `interface up` with the node silent, is the answer.

**Fix:** The tunnel, not the cluster. `scripts/ej.sh up staging` brings it up with the handshake check.

**Why:** [CLUSTER_ACCESS.md §4](CLUSTER_ACCESS.md#4--check-it-works).

## The tunnel is dead after a rebuild

**Check:** `sudo wg show` shows no handshake, and the node was rebuilt since the tunnel last worked.

**Fix:** The node generated a new WireGuard server key. Update `PublicKey =` in `~/.wireguard/<env>.conf` from the rebuilt node.

**Why:** [CLUSTER_BOOTSTRAP.md § Rebuilding a node](CLUSTER_BOOTSTRAP.md#rebuilding-a-node--including-migrating-to-arm).

## ssh refuses the key

**Check:** `ssh-add -l` says which keys the agent offers. `~/.ssh/id_ed25519_hetzner` is usually not among them.

**Fix:** Pass `-i ~/.ssh/id_ed25519_hetzner`. `scripts/shell-aliases.sh` does, for every function that reaches a node.

**Why:** [CLUSTER_ACCESS.md § Traps](CLUSTER_ACCESS.md#traps).

## The jump host refuses

**Check:** The error names `ops@10.10.0.1`, the jump, not the database node behind it.

**Fix:** `-i` reaches the destination only. Put the key in a `~/.ssh/config` block for the jump host instead.

**Why:** [CLUSTER_ACCESS.md § Two environments](CLUSTER_ACCESS.md#two-environments-and-where-this-page-assumes-one).

## kubectl fails inside ssh

**Check:** Your prompt is on the node. The error is `localhost:8080 connection refused` plus `permission denied` on `config.yaml.d`.

**Fix:** The kubeconfig is on your laptop. Run `kubectl` there. On the node itself it is `sudo k3s kubectl`.

**Why:** [CLUSTER_ACCESS.md § Traps](CLUSTER_ACCESS.md#traps).

## The staging name does not resolve

**Check:** `grep staging /etc/hosts` shows nothing.

**Fix:** Correct, and by design. There is no public record. `scripts/ej.sh up staging` prints the one `/etc/hosts` line to add.

**Why:** [CLUSTER_ACCESS.md §6](CLUSTER_ACCESS.md#6--the-site-itself).

## The certificate warning

**Check:** The issuer is Let's Encrypt's _staging_ CA. That is the design, not a fault.

**Fix:** Click through. Do not install the staging root, and do not switch the issuer to production.

**Why:** [CLUSTER_ACCESS.md §6](CLUSTER_ACCESS.md#6--the-site-itself), and [#265](https://github.com/enorm-labs/event-junkie/issues/265) for the issuer.

## A path answers with HTML that should not

**Check:** Read the `server:` and `content-type:` headers. `nginx` and `text/html` mean the SPA fallback, not the service.

**Fix:** Nothing is exposed. Any unmatched path falls through to the frontend's index page, with `404`. Check the body before you believe the status.

**Why:** [CLUSTER_ACCESS.md §6](CLUSTER_ACCESS.md#6--the-site-itself), the table _What each path should do_.

## The admin API answers 404

**Check:** The request went through the ingress. No Ingress path names the importer, on any cluster.

**Fix:** Correct. Reach it through a port-forward: `scripts/ej.sh up staging`, then `localhost:18081`.

**Why:** [CLUSTER_ACCESS.md §6a](CLUSTER_ACCESS.md#6a--the-importers-admin-api-and-seeding-staging), and ADR-023.

## The forward landed on the local stack

**Check:** The forward was `8081:8081`, and `scripts/dev-env.sh status` shows a local importer on `8081`.

**Fix:** Forward to `18081` for staging and `28081` for production. `scripts/ej.sh up` uses those ports, and only those.

**Why:** [CLUSTER_ACCESS.md §6a](CLUSTER_ACCESS.md#6a--the-importers-admin-api-and-seeding-staging).

## A port is already served

**Check:** `lsof -nP -iTCP:<port> -sTCP:LISTEN` names the process. A k9s shell is the usual one.

**Fix:** Nothing. `ej.sh` uses the forward it found and says so. `ej.sh down` leaves it alone, and you close it where you opened it.

**Why:** the header of [`scripts/ej.sh`](../../scripts/ej.sh).

## A source failed at the minute of a rollout

**Check:** The error is `Import timed out (stuck in RUNNING for >30 minutes)`. `kubectl -n event-junkie rollout history deploy/event-junkie-importer`
shows a new revision at that minute.

**Fix:** Not a scraper regression. A Flux rollout replaced the pod mid-import, and the stale-run reaper flipped the orphans to `FAILED` later. Re-run each
source on the stable pod. Seen on 2026-09-03 with four sources at once.

**Why:** the reaper in `events-importer/src/main/kotlin/de/norm/events/importing/ScheduledImportService.kt`. No runbook records this yet. This entry is the record.

## The source wrote events the site does not show

**Check:** `ej-venue <slug>`. `lastEventCount` is what the run wrote. `totalElements` is what survives as a future event.

**Fix:** A venue that publishes only past dates. Nothing to fix in the platform. The finding belongs on the scraper, as an issue.

**Why:** [DAILY_COMMANDS.md § Is one venue importing?](DAILY_COMMANDS.md#is-one-venue-importing)

## The ready filter shows nothing

**Check:** `flux get all -A` without the filter. Look for `Unknown`, which the filter omits.

**Fix:** `Unknown` is an install in progress, or a stuck one. Read the full listing after any change, never the filtered one alone.

**Why:** [DAILY_COMMANDS.md § Flux](DAILY_COMMANDS.md#flux).

## The cluster runs an old chart

**Check:** `scripts/ej.sh versions`. `running` is what the HelmRelease holds. `resolvable` is what Flux would select from the registry now.

**Fix:** Different numbers mean a publish happened and the next reconcile will move it, or the range holds it back on purpose. Production takes released
`X.Y.Z` only. If a staging snapshot resolves to an older one, compare `status.artifact.revision` with the newest tag, as
[#455](https://github.com/enorm-labs/event-junkie/issues/455) did.

**Why:** [RELEASING.md § The two version policies](RELEASING.md#the-two-version-policies).

## Production did not move after a merge

**Check:** `scripts/ej.sh versions` shows production on the last release, and the merge changed the chart.

**Fix:** Merging applies a cluster manifest, not a chart change. Production takes a released version only. Cut a release.

**Why:** [RELEASING.md § Cutting a release](RELEASING.md#cutting-a-release).

## Publishing is blocked

**Check:** `release.yml` fails at _Scan the images_ on every push to `main`, and `publish-failure-issue.yml` opened the blocker issue.

**Fix:** Usually not the change that landed. Alpine published a fix the base image does not carry yet. Look for the pull request `agent-security.yml`
opened before starting by hand. Verify any fix on `--platform linux/amd64`, because the gate scans that image.

**Why:** [RELEASING.md § Publishing is blocked](RELEASING.md#publishing-is-blocked).

## The upgrade rolled back

**Check:** `flux -n flux-system get helmrelease event-junkie` says the last attempt failed, and `kubectl describe` shows the `helm test` hook.

**Fix:** Flux retried, then rolled back on purpose. Read the test's output before touching anything. A first install is left in place instead, because
there is nothing to return to.

**Why:** [RELEASING.md § When a deploy goes wrong](RELEASING.md#when-a-deploy-goes-wrong).

## The sync cannot decrypt

**Check:** Is `flux-system` in the cluster-level `resources:` list? And did the encrypted Secret arrive in the same reconcile as the decryption patch?

**Fix:** Two causes, one message. The first is a missing entry. The second needs one `kubectl patch` by hand, and SECRETS.md has it.

**Why:** [SECRETS.md § The procedure](SECRETS.md#the-procedure), and [CLUSTER_BOOTSTRAP.md § Traps](CLUSTER_BOOTSTRAP.md#traps-in-the-order-they-bite).

## OpenObserve does not load

**Check:** `flux --context event-junkie-staging get helmrelease openobserve -n flux-system`, before you touch the tunnel.

**Fix:** A missing or malformed `openobserve-credentials` Secret leaves the release failed rather than running. That is the intended shape. Fix the Secret.

**Why:** [CLUSTER_ACCESS.md §6b](CLUSTER_ACCESS.md#6b--openobserve-for-logs-and-metrics).

## OpenObserve stops ingesting

**Check:** Count streams before you look at memory. The pod overflows at a fraction of its limit when there are too many metric names.

**Fix:** Delete the idle streams, or filter them at the collector. Raising the memory limit buys minutes.

**Why:** [OPENOBSERVE.md § The one thing that will bite you](OPENOBSERVE.md#the-one-thing-that-will-bite-you-streams-not-rows), and
[#624](https://github.com/enorm-labs/event-junkie/issues/624).

## An alert fixed in git keeps firing

**Check:** `cd deploy/alerts && ./apply.sh --diff`. `--check` reads the file, so it stays green while the cluster runs something else.

**Fix:** `./apply.sh` pushes the rules. Nothing reconciles them, so a fix reaches the cluster only when somebody runs it.

**Why:** [DAILY_COMMANDS.md § OpenObserve](DAILY_COMMANDS.md#openobserve), and [#702](https://github.com/enorm-labs/event-junkie/issues/702).

## Panels are empty

**Check:** `cd deploy/dashboards && ./apply.sh --check`. It tells no data from a broken query.

**Fix:** No data is time. A broken query is the file. Then `./apply.sh --diff` says whether the cluster runs this file at all.

**Why:** [OPENOBSERVE.md § When it misbehaves](OPENOBSERVE.md#when-it-misbehaves).

## The console is empty after a rebuild

**Check:** The node was rebuilt. Dashboard metadata lived on the PVC.

**Fix:** Expected. `cd deploy/dashboards && ./apply.sh`, then the same under `deploy/alerts`.

**Why:** [OPENOBSERVE.md § When it misbehaves](OPENOBSERVE.md#when-it-misbehaves).

## PostgreSQL refuses the tunnel address

**Check:** The error is `no pg_hba.conf entry for host`, and the client ran on your laptop.

**Fix:** The connection has to originate on the node. `ej-db` opens the SSH forward, the `psql`, and closes both. Do not widen `pg_hba`.

**Why:** [CLUSTER_ACCESS.md §7](CLUSTER_ACCESS.md#7--the-postgresql-database).

## PostgreSQL refuses the password

**Check:** PostgreSQL reports a missing role with the same message as a wrong password. `ej-psql-super`, then `\du`.

**Fix:** Create the role before you assume the Secret is wrong.

**Why:** [CLUSTER_BOOTSTRAP.md § Traps](CLUSTER_BOOTSTRAP.md#traps-in-the-order-they-bite).

## walg check fails

**Check:** `ej-backups`. The output says which of the three assertions failed.

**Fix:** Almost always `/etc/wal-g/credentials.env`. It is absent on a fresh node and destroyed by a rebuild, on purpose.

**Why:** [CLUSTER_BOOTSTRAP.md § Traps](CLUSTER_BOOTSTRAP.md#traps-in-the-order-they-bite), and [BACKUPS.md §6](BACKUPS.md#6-how-you-know-it-is-working).

## The timers are green and nothing is backed up

**Check:** `ej-backups`, never `systemctl status`. Then `pg_stat_archiver`: a rising `failed_count` is the earliest warning.

**Fix:** The timers can run while every archive fails. Fix what `walg check` names.

**Why:** [BACKUPS.md §6](BACKUPS.md#6-how-you-know-it-is-working), and
[HEALTHCHECKS.md § The two ways this quietly stops working](HEALTHCHECKS.md#the-two-ways-this-quietly-stops-working).

## The check pings nothing

**Check:** `walg check` prints `warning: HEALTHCHECK_URL is unset`. The node is not monitored, however green it looks.

**Fix:** Put the ping URL in `/etc/wal-g/credentials.env`. After a rebuild, paste the same URL back rather than creating a new check.

**Why:** [HEALTHCHECKS.md § Wiring a node to its check](HEALTHCHECKS.md#wiring-a-node-to-its-check).

## PostgreSQL is running and refuses everyone

**Check:** `sudo ss -lntp | grep 5432` on the node. `pg_settings` reports the file, not the bound address.

**Fix:** It bound loopback only, because the private address was not assigned when it started. `ip_nonlocal_bind` in `postgres.sh` prevents it. A node built
before that needs the hand-applied fix.

**Why:** [CLUSTER_BOOTSTRAP.md § Traps](CLUSTER_BOOTSTRAP.md#traps-in-the-order-they-bite).

## The database stops accepting writes

**Check:** `df /var/lib/postgresql`. A failing `archive_command` does not block writes. It accumulates WAL on a 10 GB volume.

**Fix:** Fix the archive first. Then let the backlog drain, or `pg_archivecleanup`. Never delete from `pg_wal` by hand.

**Why:** [RESTORE_RUNBOOK.md §8](RESTORE_RUNBOOK.md#8-traps-in-the-order-they-bite).

## A forward holds port 15432

**Check:** `lsof -nP -iTCP:15432 -sTCP:LISTEN`. An `ssh -f -N` left running is what you find.

**Fix:** If `scripts/ej.sh status` lists `staging/database` as up, `scripts/ej.sh down staging` stops it. Otherwise kill it. `ej-db` closes its own
forward on exit, so a survivor came from a hand-typed command.

**Why:** the database section of [`scripts/shell-aliases.sh`](../../scripts/shell-aliases.sh).

## tofu init fails without credentials

**Check:** `ls infra/<stack>/.terraform/terraform.tfstate`. If it exists, an earlier credentialed `init` left the backend record behind.

**Fix:** `InvalidAccessKeyId` here means no key, not a wrong one. Skip `init` when the providers are cached and run `validate`, or set `TF_DATA_DIR` as
`infra/AGENTS.md` shows.

**Why:** [`infra/AGENTS.md`](../../infra/AGENTS.md), the paragraph on `-backend=false`.

## A nightly workflow did not run

**Check:** The clock. Every `schedule` trigger in this repository lands 4.5 to 7 hours after its cron.

**Fix:** Nothing is wrong before midday UTC. Check again then.

**Why:** [HEALTHCHECKS.md § The cron is a request](HEALTHCHECKS.md#the-cron-is-a-request-and-github-refuses-it).

## npm warns about the engine

**Check:** `node --version` against `engines.node` in `events-frontend/package.json`.

**Fix:** The warning describes your machine, not the change. Upgrade Node, or read the lockfile diff before you roll anything back.

**Why:** `events-frontend/package.json` and `.nvmrc`, and [DEVELOPMENT.md](../DEVELOPMENT.md).

## Playwright tests the wrong checkout

**Check:** `lsof -nP -iTCP:5173 -sTCP:LISTEN`. Another checkout's dev server on that port is what the tests hit.

**Fix:** `npm run build`, then `CI=1 npx playwright test --project=chromium`, which serves this checkout's `dist/`. Do not kill the other session's server.

**Why:** [WORKTREES.md §4](../WORKTREES.md#4-take-turns-on-the-stack).

## Gradle loses classes

**Check:** `./gradlew --status` shows two daemons, one from `scripts/dev-env.sh up` and one from your test run.

**Fix:** `scripts/dev-env.sh down`, `./gradlew --stop`, then `clean` and the test. Two daemons share `build/` and clobber each other's output.

**Why:** [WORKTREES.md §4](../WORKTREES.md#4-take-turns-on-the-stack).

## gh refuses writes

**Check:** The message says `API rate limit exceeded` while `gh api rate_limit` shows thousands of points left.

**Fix:** The secondary limiter, which governs writes, and it blocks for most of an hour. Batch the writes next time: `scripts/issue-board.sh batch`.

**Why:** [the new-issue prompt](../../.github/prompts/new-issue.prompt.md), step 6.
