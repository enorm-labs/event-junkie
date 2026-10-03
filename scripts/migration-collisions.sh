#!/usr/bin/env bash
#
# migration-collisions.sh — does an open pull request add a Flyway version that `main` already holds?
#
# Usage:
#   scripts/migration-collisions.sh [--write] [<pr>...]   # default: every open pull request against main
#   scripts/migration-collisions.sh decide <main-names> <pr-names> <taken-names>
#
# Requires: gh, authenticated. Reads through the REST API and checks nothing out. Without `--write` it
# prints one verdict per pull request and exits 1 when any collides; with `--write` it sets the
# `Migration versions` commit status on each head instead. `decide` reads three files of migration
# file names, one per line, and reaches no network.
#
# `migration-versions.sh` on a merge ref is frozen at the PR's last push, so a sibling that merges
# the same number later leaves it green (#2183). `migration-versions.yml` runs this on every push to
# `main` that touches a migration, so only a PR that really collides turns red. The race left open is
# two colliding PRs merged within one run; `release.yml` stops that tree before a cluster.

set -euo pipefail

CONTEXT='Migration versions'
DIR='events-importer/src/main/resources/db/migration'

# numbered <tag> <file> — "<tag> <version> <name>" per name Flyway reads; the regex and `10#` are migration-versions.sh's.
numbered() {
  local tag="$1" name
  while IFS= read -r name; do
    [[ "$name" =~ ^V([0-9]+)__[^_].*\.sql$ ]] || continue
    echo "$tag $((10#${BASH_REMATCH[1]})) $name"
  done <"$2"
}

# decide <main-names> <pr-names> <taken-names> — prints "<state><TAB><description>", at most 140 characters as the API allows.
decide() {
  local verdict
  verdict="$({ numbered M "$1"; numbered P "$2"; numbered T "$3"; } | awk '
    { if ($2 > max) max = $2 }
    $1 == "M" { main[$2] = main[$2] " " $3; next }
    $1 == "P" { n++; pv[n] = $2; pn[n] = $3 }
    END {
      for (i = 1; i <= n; i++) {
        if (!(pv[i] in main)) continue
        k = split(main[pv[i]], names, " ")
        for (j = 1; j <= k; j++) {
          # The PR'"'"'s own file on main means it has merged, not that another file took the number.
          if (names[j] == pn[i]) continue
          if (++hits == 1) { first = sprintf("V%03d", pv[i]); file = names[j] }
          break
        }
      }
      free = sprintf("V%03d", max + 1)
      if (hits == 0 && n == 0) print "success\tAdds no migration"
      else if (hits == 0) print "success\tNo added version is taken on main"
      else printf "failure\t%s is taken on main by %s%s; renumber to %s or above\n", first, file, (hits > 1 ? " (+" hits - 1 " more)" : ""), free
    }')"
  echo "${verdict:0:140}"
}

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
  decide)
    [[ $# -eq 4 ]] || { echo "migration-collisions: decide takes three files" >&2; exit 2; }
    decide "$2" "$3" "$4"
    exit 0
    ;;
esac

write=0
if [[ "${1:-}" == "--write" ]]; then
  write=1
  shift
fi
for pr in "$@"; do
  [[ "$pr" =~ ^[0-9]+$ ]] || { echo "migration-collisions: $pr is not a pull request number" >&2; exit 2; }
done

REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

gh api "repos/$REPO/contents/$DIR?ref=main" --jq '.[] | select(.type == "file") | .name' >"$WORK/main"
# An empty listing is a failed read, not a tree without migrations; passing every PR on it would be wrong.
[[ -s "$WORK/main" ]] || { echo "migration-collisions: no migrations listed on main" >&2; exit 2; }

# added <pr> — the migration names the PR adds, a renumbering rename included.
added() {
  gh api --paginate "repos/$REPO/pulls/$1/files?per_page=100" \
    --jq ".[] | select(.status == \"added\" or .status == \"renamed\" or .status == \"copied\") | .filename
      | select(startswith(\"$DIR/\")) | ltrimstr(\"$DIR/\") | select(contains(\"/\") | not)"
}

gh api --paginate "repos/$REPO/pulls?state=open&base=main&per_page=100" --jq '.[] | "\(.number) \(.head.sha)"' >"$WORK/open"
targets=()
if [[ $# -gt 0 ]]; then
  for pr in "$@"; do targets+=("$pr $(gh api "repos/$REPO/pulls/$pr" --jq .head.sha)"); done
else
  while IFS= read -r line; do targets+=("$line"); done <"$WORK/open"
fi

# Every open PR's added versions count toward the next free number, so two renumbered PRs do not meet again.
: >"$WORK/taken"
while read -r pr _; do
  added "$pr" >"$WORK/pr-$pr"
  cat "$WORK/pr-$pr" >>"$WORK/taken"
done < <({ cat "$WORK/open"; printf '%s\n' "${targets[@]}"; } | sort -u -k1,1n)

url=''
[[ -n "${GITHUB_RUN_ID:-}" ]] && url="${GITHUB_SERVER_URL:-https://github.com}/$REPO/actions/runs/$GITHUB_RUN_ID"

collided=0
for target in "${targets[@]}"; do
  read -r pr sha <<<"$target"
  IFS=$'\t' read -r state description < <(decide "$WORK/main" "$WORK/pr-$pr" "$WORK/taken")
  printf '#%s %s %-7s %s\n' "$pr" "${sha:0:7}" "$state" "$description"
  [[ "$state" == failure ]] && collided=1
  if [[ "$write" -eq 1 ]]; then
    gh api -X POST "repos/$REPO/statuses/$sha" -f state="$state" -f context="$CONTEXT" \
      -f description="$description" ${url:+-f target_url="$url"} >/dev/null
  fi
done

[[ ${#targets[@]} -gt 0 ]] || echo "migration-collisions: no open pull request against main"
if [[ "$write" -eq 0 ]]; then exit "$collided"; fi
