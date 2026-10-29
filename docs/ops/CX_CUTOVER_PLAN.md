# Plan: production back to cx33 + cx23

This plan moves production from `cpx32` + `cpx22` to `cx33` + `cx23` in `nbg1`. The saving is about €58 a month (#4127). The issue is #4128. Delete
this file when production runs on the cx pair and the last box is ticked.

CLUSTER_BOOTSTRAP.md has the commands and the reasons for each step. See [§ Building a second pair beside the running one](CLUSTER_BOOTSTRAP.md#building-a-second-pair-beside-the-running-one) and [§ Cutting over to the second pair](CLUSTER_BOOTSTRAP.md#cutting-over-to-the-second-pair). This file gives the order, the checks and the state.

## The short version

```sh
infra/order-next-pair.sh production                  # 1. waits for cx33 + cx23, orders them, stops when the pair is complete
infra/order-next-pair.sh staging --no-fallback       # 2. after step 1: a cx33 for the rehearsal
scripts/rebuild-node.sh staging --next               # 3. the rehearsal: build, prepare, cutover, finish, apply
scripts/cutover.sh staging prepare                   #    merge #4196 while it waits
scripts/rebuild-node.sh production --next            # 4. production: build the new cluster while the old one serves
scripts/cutover.sh production prepare                #    merge #4197 while it waits, then git pull
scripts/cutover.sh production cutover                #    about 8 minutes of downtime
```

- **Production orders first, staging after.** Both wait for the same cx33 stock, and the production pair is the one that saves money.
- **Staging rehearses before production cuts over.** The production pair stays idle until the rehearsal passes. Idle, it costs about €0.06 an hour.
- **Merge a switch PR only while its `prepare` waits.** If you merge it earlier, the running app loses its database.

## State

| Item                                                                 | State                                      |
| -------------------------------------------------------------------- | ------------------------------------------ |
| `next_pair` in the module (#4165), ordering what is for sale (#4176) | merged                                     |
| `scripts/rebuild-node.sh --next` (#4185)                             | merged, not run on a real cluster          |
| `scripts/cutover.sh` (#4193)                                         | merged, not run on a real cluster          |
| Staging switch PR (#4196)                                            | draft. Merge during `staging prepare`      |
| Production switch PR (#4197)                                         | draft. Merge during `production prepare`   |
| Production pair, cx33 + cx23                                         | not ordered: no cx stock in `nbg1`         |
| Staging rehearsal pair, cx33                                         | not ordered: waits for the production pair |

## 1. Order the production pair

- [ ] `infra/order-next-pair.sh production`, in a terminal tab that stays open. It checks every 2 minutes.
- [ ] It announces `complete` with `say`. The state then holds `production-k3s-next` and `production-postgres-next`.

**A cx43 alone is the fallback.** In that case, change #4197 before you merge it. See the PR body.

## 2. Rehearse on staging

Staging has one node, so the rehearsal moves the volume to the new k3s node. It does not test a dedicated database node.

- [ ] `infra/order-next-pair.sh staging --no-fallback`
- [ ] `scripts/rebuild-node.sh staging --next`. Record the time it takes. The target is under 15 minutes after the server exists.
- [ ] `scripts/cutover.sh staging prepare`. Merge #4196 while it waits. Then `git pull`.
- [ ] `scripts/cutover.sh staging cutover`. Record the downtime that the script prints.
- [ ] Check `https://staging.event-junkie.de/` through the tunnel, and `scripts/cluster-state.sh staging`.
- [ ] `scripts/cutover.sh staging finish`, then `infra/apply.sh staging --allow-replace`. The count must be 0 to add, 2 to change, 3 to destroy.
- [ ] Write the times and every defect into #4128. Fix each defect before step 3.

## 3. Before the production window

- [ ] **Run the restore drill (#4152).** The off-server backups were never restored. Until they are, they are not proven.
- [ ] **Check the OpenObserve prefix.** The new cluster's OpenObserve writes to the same bucket prefix as the running one,
      `event-junkie-o2/production/`. Find out if two writers damage the stream, before `rebuild-node.sh production --next` runs.
- [ ] `scripts/rebuild-node.sh production --next`
- [ ] Choose a quiet hour for the window.

## 4. The production window

- [ ] Take a fresh base backup:

    ```sh
    ssh -J ops@10.10.0.1 ops@10.0.1.20 'sudo systemctl start walg-basebackup && sudo -u postgres walg check'
    ```

- [ ] `scripts/cutover.sh production prepare`. Merge #4197 while it waits. Then `git pull`.
- [ ] `scripts/cutover.sh production cutover`. Type `yes`. The site is down until step `site`.
- [ ] Check `https://event-junkie.de/`, healthchecks.io and `scripts/cluster-state.sh production`.

**If a step fails,** run `scripts/cutover.sh production cutover --from <step>` after the fix. Every step checks what is already done. **To go back,**
run `scripts/cutover.sh production rollback`. Rollback works until `finish`. Then revert #4197 before you resume Flux on the old cluster.

## 5. Finish

- [ ] `scripts/cutover.sh production finish`. It copies the state to `~/.event-junkie/cutover-production/` first.
- [ ] `infra/apply.sh production --allow-replace`, with no `-var next_pair`. Expect 0 to add, 5 to change and 6 to destroy.
- [ ] **Read the plan before you type yes.** The volume has no delete protection on production.
    - A line destroys or replaces `hcloud_volume.postgres`: answer no.
    - A line adds or replaces a server: answer no.
- [ ] Re-derive `docs/ops/COSTS.md` with its API script.
- [ ] Measure the downtime with healthchecks.io and the uptime probes. The target is under 10 minutes.
- [ ] Tick the boxes in #4128, close it, and delete this file.

## Why the database is safe

- The data stays on the `production-pgdata` volume. Nothing copies it, and nothing formats it. `postgres.sh` contains no `mkfs`.
- `cutover` stops PostgreSQL cleanly and shuts the node down before it detaches the volume.
- `postgres.sh` adopts the cluster on the volume. It does not start if the volume is not mounted, or if the major version is different.
- wal-g archives every WAL segment, and the fresh base backup of step 4 is the newest restore point.
- Until `finish`, `rollback` moves the volume back to the old database node.

Hetzner's server backups do not include the volume. They are no copy of the database.
