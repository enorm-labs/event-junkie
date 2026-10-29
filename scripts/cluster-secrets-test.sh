#!/usr/bin/env bash
#
# cluster-secrets-test.sh — assert what `cluster-secrets.sh` writes, checks and imports, against a fabricated
# Keychain and cluster.
#
# Fakes `security` (one file per item) and `kubectl` (an applied Secret is kept as a file, and `get` serves it
# back), so a write followed by `--check` runs end to end. `jq` and `base64` are the real ones. Every value
# carries the marker zq9x, and no output and no `kubectl` argument may contain it.
#
# Usage: scripts/cluster-secrets-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/cluster-secrets.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
export FAKE_KC="$WORK/keychain" FAKE_CLUSTER="$WORK/cluster" FAKE_LOG="$WORK/kubectl.log"
mkdir -p "$BIN" "$FAKE_KC" "$FAKE_CLUSTER"
export PATH="$BIN:$PATH" EJ_AGE_KEY="$WORK/age.txt"
printf 'AGE-SECRET-KEY-1zq9x\n' >"$EJ_AGE_KEY"

cat >"$BIN/security" <<'EOF'
#!/usr/bin/env bash
cmd="$1"
shift
while [ $# -gt 0 ]; do
    case "$1" in
        -s) item="$2"; shift ;;
        -w) if [ "$cmd" = add-generic-password ]; then value="$2"; shift; else print=1; fi ;;
        -a) shift ;;
    esac
    shift
done
case "$cmd" in
    find-generic-password)
        [ -f "$FAKE_KC/$item" ] || exit 44
        [ -z "${print:-}" ] || { cat "$FAKE_KC/$item"; echo; }
        ;;
    add-generic-password)
        [ ! -f "$FAKE_KC/$item" ] || exit 45
        printf '%s' "$value" >"$FAKE_KC/$item"
        ;;
esac
EOF
cat >"$BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_LOG"
shift 2
case "$1 $2" in
    "get namespace") [ -n "${FAKE_FLUX:-}" ] ;;
    "get secret")
        f="$FAKE_CLUSTER/$5-$3.json"
        [ -f "$f" ] && cat "$f"
        ;;
    apply*)
        doc="$(cat)"
        if [ "$(jq -r .kind <<<"$doc")" = Secret ]; then
            jq . <<<"$doc" >"$FAKE_CLUSTER/$(jq -r '.metadata.namespace + "-" + .metadata.name' <<<"$doc").json"
        fi
        ;;
esac
EOF
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

ITEMS="event-junkie-hcloud-token event-junkie-o2-access-key event-junkie-o2-secret-key
event-junkie-alerts-smtp-password event-junkie-scaleway-project-id event-junkie-images-access-key
event-junkie-images-secret-key event-junkie-anthropic-api-key discogs-consumer-key discogs-consumer-secret instagram-token"
for env in staging production; do
    for suffix in github-dispatch o2-root-password metrics-password bff-password tem-secret-key vapid-private-key \
        vapid-public-key imgproxy-key imgproxy-salt; do
        ITEMS="$ITEMS event-junkie-$env-$suffix"
    done
done
for item in $ITEMS; do printf 'zq9x-%s' "$item" >"$FAKE_KC/$item"; done

# run <name> <expected exit> <args…>: runs the script, keeps its output in $OUT.
run() {
    local name="$1" want="$2" got=0
    shift 2
    OUT="$("$SCRIPT" "$@" 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
    if grep -q zq9x <<<"$OUT"; then fail "$name: a value reached the output"; fi
}
expect_out() { grep -qF -- "$2" <<<"$OUT" || fail "$1: no '$2' in: $OUT"; }
secret() { [ -f "$FAKE_CLUSTER/$1.json" ]; }
key_of() { jq -r --arg k "$2" '.data[$k]' "$FAKE_CLUSTER/$1.json" | base64 -d; }

# 1. A missing Keychain item names itself and its Secret, and nothing is applied.
mv "$FAKE_KC/event-junkie-staging-vapid-public-key" "$WORK/held"
FAKE_FLUX=1 run "missing item" 1 staging
expect_out "missing item" "event-junkie-staging-vapid-public-key (for event-junkie/event-junkie-push"
[ -z "$(ls "$FAKE_CLUSTER")" ] || fail "missing item: something was applied"
mv "$WORK/held" "$FAKE_KC/event-junkie-staging-vapid-public-key"

# 2. A staging write: every object, hetzner yes, Instagram no, values exact, lengths printed.
FAKE_FLUX=1 run "staging write" 0 staging
expect_out "staging write" "event-junkie/event-junkie-push: APP_PUSH_VAPID_PRIVATE_KEY=43 APP_PUSH_VAPID_PUBLIC_KEY=42"
secret cert-manager-hetzner || fail "staging write: no hetzner"
secret event-junkie-event-junkie-instagram && fail "staging write: an Instagram Secret on staging"
secret observability-openobserve-credentials || fail "staging write: no observability copy of openobserve-credentials"
[ "$(jq -r '.data | has("O2_BASIC_AUTH_HEADER")' "$FAKE_CLUSTER/observability-openobserve-credentials.json")" = false ] ||
    fail "staging write: the header is in the observability copy"
[ "$(key_of flux-system-github-dispatch token)" = zq9x-event-junkie-staging-github-dispatch ] ||
    fail "staging write: the token was not stored exactly"
[ "$(key_of flux-system-sops-age age.agekey)" = "AGE-SECRET-KEY-1zq9x" ] || fail "staging write: the age key differs"
[ "$(key_of observability-postgres-exporter DATA_SOURCE_NAME)" = \
    "postgresql://metrics:zq9x-event-junkie-staging-metrics-password@10.1.1.10:5432/events?sslmode=require" ] ||
    fail "staging write: wrong DSN"
want_header="Basic $(printf '%s' 'hello@event-junkie.de:zq9x-event-junkie-staging-o2-root-password' | base64 | tr -d '\n')"
[ "$(key_of flux-system-openobserve-credentials O2_BASIC_AUTH_HEADER)" = "$want_header" ] || fail "staging write: wrong header"
grep -q zq9x "$FAKE_LOG" && fail "staging write: a value reached a kubectl argument"
grep -q -- '--server-side' "$FAKE_LOG" || fail "staging write: not a server-side apply"

# 3. A second write changes nothing.
cp -R "$FAKE_CLUSTER" "$WORK/before"
FAKE_FLUX=1 run "second write" 0 staging
diff -r "$WORK/before" "$FAKE_CLUSTER" >/dev/null || fail "second write: the cluster changed"

# 4. --check agrees after a write, and reports a Keychain value that moved on.
run "check same" 0 staging --check
expect_out "check same" "every key on event-junkie-staging matches"
printf 'zq9x-rotated' >"$FAKE_KC/event-junkie-alerts-smtp-password"
run "check differs" 1 staging --check
expect_out "check differs" "ZO_SMTP_PASSWORD             differs (cluster 38 bytes, Keychain 12 bytes)"
printf 'zq9x-event-junkie-alerts-smtp-password' >"$FAKE_KC/event-junkie-alerts-smtp-password"

# 5. Production: no hetzner, an Instagram Secret, its own database host, and a percent-encoded DSN password.
rm -rf "${FAKE_CLUSTER:?}"/*
printf 'zq9x@p#w' >"$FAKE_KC/event-junkie-production-metrics-password"
FAKE_FLUX=1 run "production write" 0 production
secret cert-manager-hetzner && fail "production write: a hetzner Secret on production"
secret event-junkie-event-junkie-instagram || fail "production write: no Instagram Secret"
key_of observability-postgres-exporter DATA_SOURCE_NAME | grep -qF 'metrics:zq9x%40p%23w@10.0.1.20:5432/' ||
    fail "production write: the DSN host or encoding is wrong"

# 6. Before flux bootstrap, flux-system is skipped and said so.
rm -rf "${FAKE_CLUSTER:?}"/*
run "no flux" 0 staging
expect_out "no flux" "flux-system/github-dispatch: skipped"
secret flux-system-github-dispatch && fail "no flux: wrote into flux-system"
secret event-junkie-event-junkie-push || fail "no flux: skipped event-junkie too"

# 7. --import fills an absent item from the cluster and leaves every present one alone.
FAKE_FLUX=1 run "import setup" 0 staging
rm "$FAKE_KC/event-junkie-staging-vapid-private-key"
printf 'zq9x-kept' >"$FAKE_KC/event-junkie-images-access-key"
run "import" 0 staging --import
expect_out "import" "event-junkie-staging-vapid-private-key: imported from event-junkie/event-junkie-push (43 bytes)"
expect_out "import" "imported 1 Keychain item(s)"
[ "$(cat "$FAKE_KC/event-junkie-staging-vapid-private-key")" = zq9x-event-junkie-staging-vapid-private-key ] ||
    fail "import: the imported value differs"
[ "$(cat "$FAKE_KC/event-junkie-images-access-key")" = zq9x-kept ] || fail "import: overwrote a present item"

# 8. Another context, and a skipped Secret: nothing written there, every other one is.
rm -rf "${FAKE_CLUSTER:?}"/*
: >"$FAKE_LOG"
FAKE_FLUX=1 EJ_KUBE_CONTEXT=event-junkie-staging-next EJ_SKIP_SECRETS="flux-system/github-dispatch" run "skip and context" 0 staging
secret flux-system-github-dispatch && fail "skip and context: github-dispatch was written"
secret flux-system-sops-age || fail "skip and context: sops-age missing"
grep -q -- '--context event-junkie-staging-next' "$FAKE_LOG" || fail "skip and context: wrong context: $(head -1 "$FAKE_LOG")"
grep -q -- '--context event-junkie-staging ' "$FAKE_LOG" && fail "skip and context: reached the running cluster"

if [ "$FAILURES" -gt 0 ]; then
    echo "cluster-secrets-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "cluster-secrets-test: all cases pass"
