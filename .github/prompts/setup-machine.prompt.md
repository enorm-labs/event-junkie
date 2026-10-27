# Setup Machine

A new Mac to a working Event Junkie checkout. `scripts/setup-mac.sh` does the mechanical part and reports what is missing. This command reads that report,
hands the operator each step only a person can do, and checks again until nothing is missing.

## Important

- **The operator runs every command that installs, prompts or uses `sudo`.** `brew bundle` asks for the password on a cask, `setup-mac.sh operator` reads
  secrets with `security -w`, and `wg-quick` needs root. Give the exact command, one block at a time, without a `!` prefix. You run only
  `scripts/setup-mac.sh check` and the read-only checks in Step 5.
- **A secret never passes through this conversation.** Do not ask for a value, and do not print one. Keychain items and key files come from KeePass;
  [docs/CREDENTIALS.md](../../docs/CREDENTIALS.md) numbers each one, and the report names the number.
- **Ask one question first: contributor or operator.** A contributor builds, tests and runs the local stack. An operator also reaches the clusters.

## Step 0 — Before this command can run

The operator does this by hand. Skip what already works.

```sh
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"
brew install gh && gh auth login
gh repo clone enorm-labs/event-junkie ~/repos/event-junkie
brew install --cask claude-code && cd ~/repos/event-junkie && claude
```

## Step 1 — Read the state

Run `scripts/setup-mac.sh check`, or `scripts/setup-mac.sh check operator`. Group the `--` lines by the step below that fixes them. When nothing is
missing, go to Step 5.

## Step 2 — The contributor tier

1. The operator runs `scripts/setup-mac.sh contributor`. It installs the `Brewfile`, the JDK from `.sdkmanrc`, Node from `events-frontend/.nvmrc`, both apps'
   `node_modules`, squawk and the commit hooks.
2. The operator starts Docker Desktop once and accepts its terms. `bootRun` and the Testcontainers tests fail until the daemon runs.
3. For each `add to ~/.zshrc` line in the report, give the line. The operator adds it and opens a new terminal.
4. The operator restarts `claude` in the repository and accepts the `fluxcd` marketplace and its plugin when Claude Code asks. `.claude/settings.json`
   declares both.
5. `http/http-client.private.env.json` is a copy of the template. The Google Maps key in it is needed only for `scripts/geocode-venues.py` and `/add-venue`.

## Step 3 — The operator tier

1. The operator saves each missing key file from its KeePass attachment to the path the report names: the age key (#16), the SSH key (#17), the WireGuard
   configs (#18, #18a).
2. The operator runs `scripts/setup-mac.sh operator`. It asks for each missing Keychain item, writes the node block into `~/.ssh/config` and creates both
   `.envrc` files from their templates.
3. The `/etc/hosts` line, if the report lists it, as the report prints it.
4. For each environment: `sudo wg-quick up ~/.wireguard/staging.conf`, then `scripts/setup-mac.sh kubeconfig staging`. The second command fetches the
   kubeconfig over SSH and merges it into `~/.kube/config`.

**One WireGuard key on two Macs works, but not at the same time.** The node accepts the most recent address of a key, so two tunnels with the same key take
the connection from each other. Bring the tunnel down on the old Mac first.

## Step 4 — What the script does not carry

Tell the operator about each of these that applies:

- **Files that are not in git or KeePass.** Copy them from the old machine if they exist: a filled `http/http-client.private.env.json`, an `.envrc` with
  local additions, and `events-frontend/public/map/` (or run `scripts/map-assets.sh dev` again).
- **Claude Code state is per machine.** Personal plugins, `~/.claude/settings.json` and the auto-memory under `~/.claude/projects/` stay on the old Mac
  until the operator copies them.
- **A worktree commit needs `node_modules` in the main checkout, at the pinned oxfmt version.** A worktree without its own `node_modules` borrows the main
  checkout's oxfmt for the Markdown hook. After an oxfmt bump, run `npm ci` in the main checkout's `events-frontend/` again.

## Step 5 — Prove it

Run the checks for the tier. Report each result.

- Contributor: `scripts/setup-mac.sh check` reports nothing missing. Then `scripts/dev-env.sh up all`, `scripts/dev-env.sh seed-fixture`, and
  `curl -s localhost:8080/api/events | head -c 200`. Finish with `scripts/dev-env.sh down all`.
- Operator, in addition: `scripts/setup-mac.sh check operator`, then `scripts/ej.sh up staging` and `scripts/ej.sh status`. Finish with
  `scripts/ej.sh down staging`.

End with what works now and the lines that are still missing, each with its fix.
