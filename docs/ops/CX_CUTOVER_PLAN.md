# Plan: production back to cx33 + cx23

This plan moves production from `cpx32` + `cpx22` to `cx33` + `cx23` in `nbg1`. The saving is about €58 a month (#4127). The issue is #4128. Delete
this file when production runs on the cx pair and the last box is ticked.

CLUSTER_BOOTSTRAP.md has the commands and the reasons for each step. See [§ Building a second pair beside the running one](CLUSTER_BOOTSTRAP.md#building-a-second-pair-beside-the-running-one) and [§ Cutting over to the second pair](CLUSTER_BOOTSTRAP.md#cutting-over-to-the-second-pair). This file gives the order, the checks and the state.

## The short version

```sh
scripts/cutover.sh production prepare                # merge #4197 while it waits, then git pull
scripts/cutover.sh production cutover                # about 8 minutes of downtime
scripts/cutover.sh production finish                 # then infra/apply.sh production --allow-replace
```

- **Merge #4197 only while `prepare` waits.** If you merge it earlier, the running app loses its database.
- **Cut no release between `prepare` and `finish`.** The first install on the new cluster and a rollback both take the newest chart.
- **Do the cutover before 2026-12-27.** The certificates renew on that date. After it, the new cluster needs Let's Encrypt directly after `resume`.
- **Staging does not rehearse this cutover.** See [Background and history](#background-and-history).

## State

| Item                                                                 | State                                                                                       |
| -------------------------------------------------------------------- | ------------------------------------------------------------------------------------------- |
| `next_pair` in the module (#4165), ordering what is for sale (#4176) | merged                                                                                      |
| `scripts/rebuild-node.sh --next` (#4185)                             | merged, ran on production on 2026-10-30                                                     |
| `scripts/cutover.sh` (#4193, #4488)                                  | merged, not run on a real cluster                                                           |
| Production switch PR (#4197)                                         | draft, sets OpenObserve's prefix `production-2/` (#4302). Merge during `production prepare` |
| Production pair, cx33 + cx23                                         | built: `production-k3s-next` and `production-postgres-next`                                 |

## 1. Order the production pair

- [x] `infra/order-next-pair.sh production`, in a terminal tab that stays open. It checks every 2 minutes.
- [x] It announces `complete` with `say`. The state then holds `production-k3s-next` and `production-postgres-next`.

It ordered a cx33, not the cx43 fallback, so #4197 needs no change.

## 2. Before the production window

- [x] **Run the restore drill (#4152).** It passed on staging on 2026-10-31. Restore to serving took about 13 seconds. See BACKUPS.md §9.
- [x] **Check the OpenObserve prefix.** Two writers on `production/` do not damage the stream: each instance reads, compacts and expires only
      what its own file list holds. The old instance's files then need a new prefix and a `retire-*` rule (#4302).
- [x] `scripts/rebuild-node.sh production --next`
- [ ] **Check the new cluster.** Bring its tunnel up with `scripts/cluster-access.sh production --next`. Both tunnels use one address, so a kube context fails
      with `x509: certificate signed by unknown authority` when the other tunnel is up. This cause is probable, not proven. Then check these items with the context `event-junkie-production-next`:
    - `get endpoints kubernetes` shows only `10.0.1.11:6443`.
    - Every pod is `Running` or `Completed`.
    - The Kustomization `cluster-issuer` and the ClusterIssuer `letsencrypt-production` are ready.
    - The Secrets `event-junkie-tls` and `event-junkie-tls-redirect` keep their `cert-manager.io/issuer-name` and `cert-manager.io/alt-names` annotations.
- [ ] Choose a quiet hour, and keep about an hour free.

## 3. The production window

- [ ] Turn off the corporate TLS proxy. If it stays on, step `site` can fail its certificate check while the site works.
- [ ] Load the SSH key into the agent with `ssh-add`. The script's SSH cannot ask for a passphrase.
- [ ] Check that no release cut is running or planned until `finish`.
- [ ] Take a fresh base backup:

    ```sh
    ssh -J ops@10.10.0.1 ops@10.0.1.20 'sudo systemctl start walg-basebackup && sudo -u postgres walg check'
    ```

- [ ] Copy the database to this Mac. It is a second copy, outside Hetzner:

    ```sh
    scripts/db-dump.sh production
    ```

- [ ] `scripts/cutover.sh production prepare`. Merge #4197 while it waits. Then `git pull`.
- [ ] `scripts/cutover.sh production cutover`. Type `yes`. The site is down until step `site`.
- [ ] Change `10.0.1.20` to `10.0.1.21` in the `Host` line of `~/.ssh/config`. Your own SSH commands then use the right key. The scripts do not need this.
- [ ] Check `https://event-junkie.de/`, healthchecks.io and `scripts/cluster-state.sh production`.
- [ ] Compare the row counts with the dump: `scripts/db-dump.sh production --check <the .counts file it printed>`. The importer writes after `resume`, so
      a difference in `events.event` or `events.event_source` after that is expected.

**If a step fails,** run `scripts/cutover.sh production cutover --from <step>` after the fix. Every step checks what is already done. **To go back,**
run `scripts/cutover.sh production rollback`. Rollback works until `finish`. Then revert #4197 before you resume Flux on the old cluster.

Rollback gives the volume back to `postgres` on the old node and starts PostgreSQL there. It adds the old cluster's deploy key back if `follow-up` deleted it. It does the key last, so GitHub cannot hold the
site down. `prepare` suspends only the `flux-system` Kustomization. The Kustomization `cluster-issuer` and the chart's OCIRepository on the old cluster do not stop.

## 4. Finish

- [ ] `scripts/cutover.sh production finish`. It copies the state to `~/.event-junkie/cutover-production/` first.
- [ ] `infra/apply.sh production --allow-replace`, with no `-var next_pair`. Expect 0 to add, 5 to change and 6 to destroy.
- [ ] Add the `retire-production` rule for `production/` to `infra/bootstrap/storage.tf`, and apply bootstrap. See OPENOBSERVE.md § Changing the prefix.
      On 15 days after the apply, check that `production/` is empty, then remove the rule. This check is also the first proof that Hetzner applies a prefix filter.
- [ ] **Read the plan before you type yes.** The volume has no delete protection on production.
    - A line destroys or replaces `hcloud_volume.postgres`: answer no.
    - A line adds or replaces a server: answer no.
- [ ] Re-derive `docs/ops/COSTS.md` with its API script.
- [ ] Measure the downtime with healthchecks.io and the uptime probes. The target is under 10 minutes.
- [ ] Delete the dumps in `~/.event-junkie/dumps/` when the restore drill and the counts pass. A production dump holds personal data.
- [ ] Tick the boxes in #4128, close it, and delete this file.

## Why the database is safe

- The data stays on the `production-pgdata` volume. Nothing copies it, and nothing formats it. `postgres.sh` contains no `mkfs`.
- `cutover` stops PostgreSQL cleanly and shuts the node down before it detaches the volume.
- `postgres.sh` adopts the cluster on the volume. It does not start if the volume is not mounted, or if the major version is different.
- wal-g archives every WAL segment, and the fresh base backup of section 3 is the newest restore point.
- Until `finish`, `rollback` moves the volume back to the old database node.

Hetzner's server backups do not include the volume. They are no copy of the database.

## Background and history

Staging was to rehearse this cutover first (#4196). Its second pair needs two more Primary IPs, and the project holds 10, with none free. Hetzner's list does not show the limit, so 10 is probably the limit. Hetzner refused the order with `Primary IP limit exceeded`. Staging already runs on cx33, so it saves nothing by a move. The rehearsal also could not test a dedicated database node, because staging has one node. Production therefore goes without it, and staging keeps its node and its prefix `staging/`.
