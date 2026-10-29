#!/usr/bin/env bash
#
# setup-mac.sh — a new Mac to a working Event Junkie checkout, and a report of what is still missing.
#
# Usage:
#   scripts/setup-mac.sh check [operator]               # report only; `operator` adds keychain, key files, cluster access
#   scripts/setup-mac.sh contributor                    # Brewfile, JDK, Node, npm ci, hooks, private env template
#   scripts/setup-mac.sh operator                       # contributor, plus the cluster tools, keychain items, SSH config, .envrc files
#   scripts/setup-mac.sh kubeconfig <staging|production>  # fetch and merge one cluster's kubeconfig; the tunnel must be up
#
# Every step is idempotent and skips what is already in place. Nothing runs sudo and nothing
# edits a shell profile: the lines a profile still needs are printed instead. `operator` prompts
# for each missing Keychain item with `security -w`, which reads the value without echoing it,
# so run it in a terminal of your own, not through an agent. Secret values come from KeePass;
# docs/CREDENTIALS.md numbers each one.
#
# Reaches Homebrew, get.sdkman.io, nvm and npm. `check` reads the gh token's scopes from api.github.com. `kubeconfig` reaches the node over SSH.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok() { printf '  \033[1;32mok\033[0m  %s\n' "$*"; }
miss() {
    printf '  \033[1;33m--\033[0m  %s\n' "$*"
    missing=$((missing + 1))
}
# A state that is normal between sessions, not a setup gap: shown, not counted.
warn() { printf '  \033[1;33m!!\033[0m  %s\n' "$*"; }
die() {
    printf '\033[1;31mxx\033[0m %s\n' "$*" >&2
    exit 1
}

missing=0

# Keychain service name and its docs/CREDENTIALS.md entry.
KEYCHAIN_ITEMS=(
    "event-junkie-anthropic-api-key #26"
    "event-junkie-hcloud-token #9"
    "event-junkie-s3-access-key #10"
    "event-junkie-s3-secret-key #10"
    "event-junkie-o2-access-key #10a"
    "event-junkie-o2-secret-key #10a"
    "event-junkie-staging-github-dispatch #15"
    "event-junkie-production-github-dispatch #15a"
    "event-junkie-staging-healthcheck-url #21"
    "event-junkie-production-healthcheck-url #21"
    "event-junkie-production-events-db #20"
    "event-junkie-staging-o2-root-password #8"
    "event-junkie-staging-metrics-password metrics-role"
    "discogs-consumer-key #27"
    "discogs-consumer-secret #27"
    "instagram-token #28"
)

# Path and its docs/CREDENTIALS.md entry; each is a KeePass attachment.
KEY_FILES=(
    "$HOME/.config/sops/age/event-junkie.txt #16"
    "$HOME/.ssh/id_ed25519_hetzner #17"
    "$HOME/.wireguard/staging.conf #18"
    "$HOME/.wireguard/production.conf #18a"
)

SSH_HOSTS="Host 10.10.0.1 10.10.1.1 10.0.1.21"

brew_prefix() { brew --prefix "$1" 2>/dev/null; }

# The official installer's ~/.sdkman, or an older Homebrew install; the sdkman/tap formula lags upstream.
sdkman_dir() {
    if [ -n "${SDKMAN_DIR:-}" ]; then
        echo "$SDKMAN_DIR"
    elif [ -d "$HOME/.sdkman" ]; then
        echo "$HOME/.sdkman"
    elif [ -n "$(brew_prefix sdkman-cli)" ]; then
        echo "$(brew_prefix sdkman-cli)/libexec"
    fi
}

# sdkman-init.sh and nvm.sh both read unset variables.
load_sdkman() {
    set +u
    SDKMAN_DIR="$(sdkman_dir)"
    export SDKMAN_DIR
    # shellcheck disable=SC1091
    source "$SDKMAN_DIR/bin/sdkman-init.sh"
    set -u
}
load_nvm() {
    set +u
    export NVM_DIR="$HOME/.nvm"
    mkdir -p "$NVM_DIR"
    # shellcheck disable=SC1091
    source "$(brew_prefix nvm)/nvm.sh"
    set -u
}

require_brew() {
    command -v brew >/dev/null ||
        die 'no Homebrew — install it from https://brew.sh, then run this again'
}

check_tools() {
    log "tools"
    local tool
    for tool in git gh jq yq direnv psql uv docker ijhttp claude kotlin-lsp typescript-language-server pre-commit gitleaks shellcheck shfmt ruff hadolint tofu helm flux lychee squawk; do
        if command -v "$tool" >/dev/null; then ok "$tool"; else miss "$tool"; fi
    done
    # `docker version` exits zero on the client half alone; `docker info` fails when the daemon does not answer.
    if command -v docker >/dev/null; then
        if docker info >/dev/null 2>&1; then ok "docker daemon"; else warn "docker daemon not running — start Docker Desktop (/setup-machine Step 2.2)"; fi
    fi
    if [ "${1:-}" = operator ]; then
        for tool in wg-quick kubectl kubectx k9s sops age; do
            if command -v "$tool" >/dev/null; then ok "$tool"; else miss "$tool"; fi
        done
    fi
}

check_runtimes() {
    log "runtimes"
    local want
    want="$(sed -n 's/^java=//p' .sdkmanrc)"
    if [ -n "$(sdkman_dir)" ] && [ -d "$(sdkman_dir)/candidates/java/$want" ]; then ok "JDK $want"; else miss "JDK $want — scripts/setup-mac.sh contributor"; fi
    if [ -n "$(brew_prefix nvm)" ] && load_nvm && nvm ls "$(cat events-frontend/.nvmrc)" >/dev/null 2>&1; then
        ok "Node $(cat events-frontend/.nvmrc)"
    else
        miss "Node $(cat events-frontend/.nvmrc) — scripts/setup-mac.sh contributor"
    fi
    local app
    for app in events-frontend events-admin; do
        if [ -d "$app/node_modules" ]; then ok "$app/node_modules"; else miss "$app/node_modules — npm ci in $app"; fi
    done
}

check_repo() {
    log "this checkout"
    if [ -f "$(git rev-parse --git-common-dir)/hooks/pre-commit" ]; then ok "pre-commit hook"; else miss "pre-commit hook — pre-commit install"; fi
    if [ "$(git config blame.ignoreRevsFile || true)" = .git-blame-ignore-revs ]; then ok "blame.ignoreRevsFile"; else miss "blame.ignoreRevsFile"; fi
    if [ -f http/http-client.private.env.json ]; then ok "http/http-client.private.env.json"; else miss "http/http-client.private.env.json"; fi
    # `gh auth status` can hang; reading the token cannot.
    if gh auth token >/dev/null 2>&1; then
        ok "gh logged in"
        check_gh_scope
    else
        miss "gh — gh auth login -s project"
    fi
}

# scripts/issue-board.sh needs the `project` scope. A fine-grained token sends no X-OAuth-Scopes header.
check_gh_scope() {
    local headers line
    if ! headers="$(gh api -i /user 2>/dev/null)"; then
        warn "gh project scope — GitHub did not answer, not checked"
        return
    fi
    line="$(printf '%s\n' "$headers" | tr -d '\r' | grep -i '^x-oauth-scopes:' || true)"
    if [ -z "$line" ]; then
        ok "gh project scope — scopes not listed"
    elif printf '%s\n' "${line#*:}" | tr ',' '\n' | tr -d ' ' | grep -qx project; then
        ok "gh project scope"
    else
        miss "gh project scope — gh auth refresh -s project"
    fi
}

check_profile() {
    log "shell profile (~/.zshrc)"
    local entry marker line
    while IFS= read -r entry; do
        marker="${entry%%|*}" line="${entry#*|}"
        if grep -qF "$marker" "$HOME/.zshrc" 2>/dev/null; then ok "$marker"; else miss "add to ~/.zshrc: $line"; fi
    done <<'EOF'
direnv hook zsh|eval "$(direnv hook zsh)"
sdkman-init.sh|export SDKMAN_DIR="$HOME/.sdkman" && source "$SDKMAN_DIR/bin/sdkman-init.sh"
nvm.sh|export NVM_DIR="$HOME/.nvm" && source "$(brew --prefix nvm)/nvm.sh"
EOF
}

check_operator() {
    log "keychain"
    local entry name ref
    for entry in "${KEYCHAIN_ITEMS[@]}"; do
        name="${entry% *}" ref="${entry##* }"
        if security find-generic-password -s "$name" >/dev/null 2>&1; then ok "$name"; else miss "$name (KeePass $ref)"; fi
    done
    log "key files"
    local path
    for entry in "${KEY_FILES[@]}"; do
        path="${entry% *}" ref="${entry##* }"
        if [ ! -f "$path" ]; then
            miss "$path — the attachment of KeePass $ref"
        elif [ "$(stat -f %Lp "$path")" != 600 ]; then
            miss "$path is $(stat -f %Lp "$path"), not 600"
        else
            ok "$path"
        fi
    done
    log "cluster access"
    if grep -qF "$SSH_HOSTS" "$HOME/.ssh/config" 2>/dev/null; then ok "$HOME/.ssh/config block"; else miss "$HOME/.ssh/config block — scripts/setup-mac.sh operator"; fi
    if grep -q 'staging\.event-junkie\.de' /etc/hosts; then
        ok "/etc/hosts staging.event-junkie.de"
    else
        miss "/etc/hosts — sudo sh -c 'echo \"10.10.1.1  staging.event-junkie.de\" >> /etc/hosts'"
    fi
    local env
    for env in staging production; do
        if kubectl config get-contexts "event-junkie-$env" >/dev/null 2>&1; then
            ok "kubectl context event-junkie-$env"
        else
            miss "kubectl context event-junkie-$env — tunnel up, then scripts/setup-mac.sh kubeconfig $env"
        fi
    done
    local envrc
    for envrc in .envrc infra/.envrc; do
        if [ -f "$envrc" ]; then ok "$envrc"; else miss "$envrc — scripts/setup-mac.sh operator"; fi
    done
}

check() {
    check_tools "${1:-}"
    check_runtimes
    check_repo
    check_profile
    [ "${1:-}" = operator ] && check_operator
    echo
    if [ "$missing" -eq 0 ]; then log "nothing missing"; else log "$missing missing"; fi
}

contributor() {
    require_brew
    log "brew bundle"
    # Installs what is missing and leaves an installed version alone.
    brew bundle --no-upgrade --file "$REPO_ROOT/Brewfile"

    if [ -z "$(sdkman_dir)" ]; then
        log "SDKMAN, without touching the shell profile"
        curl -fsSL "https://get.sdkman.io?rcupdate=false" | bash
    fi
    log "JDK from .sdkmanrc"
    load_sdkman
    set +u
    sdk env install
    set -u

    log "Node from events-frontend/.nvmrc"
    load_nvm
    (cd events-frontend && nvm install)
    local app
    for app in events-frontend events-admin; do
        # npm writes node_modules/.package-lock.json on every install; newer than the lock means in sync.
        if [ "$app/node_modules/.package-lock.json" -nt "$app/package-lock.json" ]; then
            ok "$app/node_modules"
            continue
        fi
        log "npm ci in $app"
        (cd "$app" && nvm exec --silent npm ci)
    done

    command -v squawk >/dev/null || uv tool install squawk-cli

    log "commit hooks"
    pre-commit install
    git config blame.ignoreRevsFile .git-blame-ignore-revs

    [ -f http/http-client.private.env.json ] ||
        cp http/http-client.private.env.json.example http/http-client.private.env.json

    log "helm and flux plugins"
    scripts/plugin-parity.sh || true
}

store_keychain_items() {
    log "keychain — Enter at the prompt skips an item"
    local entry name ref answer
    for entry in "${KEYCHAIN_ITEMS[@]}"; do
        name="${entry% *}" ref="${entry##* }"
        security find-generic-password -s "$name" >/dev/null 2>&1 && continue
        read -r -p "  store $name (KeePass $ref)? [y/N] " answer
        [ "$answer" = y ] || continue
        security add-generic-password -a "$USER" -s "$name" -w
    done
}

operator() {
    EJ_OPERATOR=1 contributor
    store_keychain_items

    mkdir -p "$HOME/.ssh" "$HOME/.wireguard" "$HOME/.config/sops/age"
    chmod 700 "$HOME/.ssh" "$HOME/.wireguard"
    local entry path
    for entry in "${KEY_FILES[@]}"; do
        path="${entry% *}"
        [ -f "$path" ] && chmod 600 "$path"
    done

    if ! grep -qF "$SSH_HOSTS" "$HOME/.ssh/config" 2>/dev/null; then
        log "appending the node block to ~/.ssh/config (docs/ops/CLUSTER_ACCESS.md)"
        printf '\n%s\n    User ops\n    IdentityFile ~/.ssh/id_ed25519_hetzner\n    IdentitiesOnly yes\n' "$SSH_HOSTS" >>"$HOME/.ssh/config"
        chmod 600 "$HOME/.ssh/config"
    fi

    local envrc
    for envrc in .envrc infra/.envrc; do
        [ -f "$envrc" ] || cp "$envrc.example" "$envrc"
        direnv allow "$envrc"
    done
}

kubeconfig() {
    local env="${1:-}" tunnel_ip
    case "$env" in
        staging) tunnel_ip=10.10.1.1 ;;
        production) tunnel_ip=10.10.0.1 ;;
        *) die "kubeconfig takes staging or production" ;;
    esac
    local name="event-junkie-$env" file="$HOME/.kube/event-junkie-$env"
    nc -z -G 3 "$tunnel_ip" 22 2>/dev/null ||
        die "no answer from $tunnel_ip — sudo wg-quick up ~/.wireguard/$env.conf first"

    mkdir -p "$HOME/.kube"
    # k3s names the cluster, the user and the context `default`; two of those would collide on merge.
    ssh "ops@$tunnel_ip" sudo cat /etc/rancher/k3s/k3s.yaml |
        sed -E -e "s|127\.0\.0\.1|$tunnel_ip|" \
            -e "s/^([[:space:]]*-?[[:space:]]*(name|cluster|user|current-context):) default$/\1 $name/" >"$file"
    chmod 600 "$file"
    log "wrote $file"

    if kubectl config get-contexts "$name" >/dev/null 2>&1; then
        log "context $name is already in ~/.kube/config — left as it is"
        return
    fi
    [ -f "$HOME/.kube/config" ] && cp "$HOME/.kube/config" "$HOME/.kube/config.bak.$(date +%Y%m%d)"
    KUBECONFIG="$HOME/.kube/config:$file" kubectl config view --flatten >"$HOME/.kube/config.merged"
    mv "$HOME/.kube/config.merged" "$HOME/.kube/config"
    chmod 600 "$HOME/.kube/config"
    kubectl --context "$name" --request-timeout=10s get nodes
}

case "${1:-}" in
    check) check "${2:-}" ;;
    contributor)
        contributor
        check
        ;;
    operator)
        operator
        check operator
        ;;
    kubeconfig) kubeconfig "${2:-}" ;;
    *) die "unknown command '${1:-}' — scripts/setup-mac.sh --help" ;;
esac
