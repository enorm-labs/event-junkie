# Reboot Nodes

Clear `ej-reboot-pending` and `ej-service-restart-pending`: find out what waits on each node, hand the operator one command that reboots the nodes in order,
then prove the alerts stop. `scripts/node-reboot.sh` does the reboot and every check. This prompt is the part around it.

## Important

- **The operator runs the reboot, never the agent.** `scripts/node-reboot.sh reboot` runs `sudo systemctl reboot` over SSH, and the guard refuses that. Hand
  over the one command and wait for the output.
- **`check` is yours.** It only reads: needrestart, the reboot flag, the importer's sources. Run it before and after.
- **The production site is down for about 2 minutes** while the k3s node reboots. Say so when you hand over the command.
- **A tunnel or forward that is down is the operator's step** (`scripts/ej.sh up <env>` takes `sudo`). Say so and stop.

## Arguments

```text
/reboot-nodes [staging|production|all]
```

`all` is the default.

## Steps

1. **Read what waits.** `scripts/node-reboot.sh check <scope>`. Report each node in one line: nothing waits, or the services and the kernel.
   Nothing waits anywhere: say so and stop. The alert clears at its next evaluation.
2. **Hand over the command.** `scripts/node-reboot.sh reboot <scope>`, bare, for a paste into zsh. It lists the nodes, asks `Go? [y/N]`, waits for running
   imports, and stops at the first failed check.
3. **Read the output the operator pastes.** Every node ends with `Kubernetes events arrive` (k3s nodes) or `site and /api/events answer 200` (production
   database node), and the run with `done`. A `FAIL` line names the check. Read the logs and the node before you propose a next step. Do not rerun the
   reboot.
4. **Prove it.** `scripts/node-reboot.sh check <scope>` says nothing waits. The two node metrics read 0 in OpenObserve:

    ```sh
    scripts/o2-query.sh <env> promql 'max by (node) (node_service_restart_pending_age_seconds) + max by (node) (node_reboot_required_age_seconds)' --hours 1 --step 600
    ```

    The last value of each node is 0. The alert stops at its next evaluation.

5. **Report** each node, when it came back, and anything the run restarted, such as the gateway collector.

## Notes

- **A wait loop that never returns** after a reboot is an SSH session that hung while the node went down. The script sets `ServerAliveInterval`, so this
  does not happen in it. A hand-typed loop without it needs Ctrl-C.
- **An import that starts between the check and the reboot ends `FAILED`** ("stuck in RUNNING for >30 minutes"). It recovers at its next scheduled run.
- **The order is fixed**: the production database node, the production k3s node, staging. [CLUSTER_BOOTSTRAP.md](../../docs/ops/CLUSTER_BOOTSTRAP.md#rebooting-a-node)
  § Rebooting a node says why.
