#!/usr/bin/env bash
#
# admin-screenshots.sh — before and after screenshots of events-admin pages for a pull request: the base ref against
# the branch, each built and served on a free port with JSON fixtures behind /api/admin, never an importer.
#
# Usage:
#   scripts/admin-screenshots.sh [options] <path>...  # paths such as /sources, /worklist or /events/new
#     --base <ref>              before side (default origin/main)
#     --tree <dir>              after side: a checkout of the branch (default this one)
#     --fixtures <dir>          default events-admin/screenshot-fixtures of this checkout, so a branch without them works
#     --widths 390,1280         viewport widths; a width under 768 is a phone
#     --themes light,dark       dark adds shadcn-vue's `.dark` class to <html>; the app has no toggle of its own
#     --click <selector>        click the first match
#     --fill <selector> <text>  type into the first match
#     --select <selector> <label>  pick an option of the first <select> match by its label
#     --scroll <selector>       scroll the first match to the middle of the frame
#     --wait <ms>               wait, such as for a poll to answer
#     --label <name>            added to the file names, so a run with steps does not overwrite the plain one
#     --full-page               the whole page instead of the viewport
#     --out <dir>               default build/admin-screenshots
#   The step options run in the order given, after the page loads. A step whose selector matches nothing on a side
#   is skipped there with a message, so the before side of a new button shows the page without it.
#   Then, from the repository root: gh pr edit <n> --body-file <body.md> --attach <png>...
#
# Requires: Node, git, events-admin's node_modules in --tree and events-frontend's in this checkout, with Playwright's
# Chromium (`npx playwright install chromium`). Reaches no network: scripts/admin-fixture-server.mjs serves each
# dist/ and answers the API from the fixtures. Each shot starts from the fixtures as committed: a step that wrote
# (an import sets a row RUNNING) is undone before the next shot. Writes nothing outside --out.
#
# WHY NOT THE DEV SERVER: the admin app runs as a dev server on 5174 against a cluster port-forward (ADR-045). Both
# belong to other sessions, and a screenshot needs neither. A dist/ built with `--mode fixtures` shows "fixtures" in
# the header badge where the dev server shows the cluster.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_REF="origin/main"
TREE="$REPO_ROOT"
FIXTURES="$REPO_ROOT/events-admin/screenshot-fixtures"
OUT="$REPO_ROOT/build/admin-screenshots"
WIDTHS="390,1280"
THEMES="light,dark"
LABEL=""
FULL_PAGE="false"
STEPS=""
PATHS=()
TAB=$'\t'

die() {
    echo "admin-screenshots: $*" >&2
    exit 1
}

step() {
    STEPS+="$1$TAB$2$TAB${3:-}"$'\n'
}

while (($#)); do
    case "$1" in
        --base)
            BASE_REF="${2:?--base needs a ref}"
            shift 2
            ;;
        --tree)
            TREE="$(cd "${2:?--tree needs a directory}" && pwd)"
            shift 2
            ;;
        --fixtures)
            FIXTURES="$(cd "${2:?--fixtures needs a directory}" && pwd)"
            shift 2
            ;;
        --out)
            OUT="${2:?--out needs a directory}"
            shift 2
            ;;
        --widths)
            WIDTHS="${2:?}"
            shift 2
            ;;
        --themes)
            THEMES="${2:?}"
            shift 2
            ;;
        --click | --scroll)
            step "${1#--}" "${2:?$1 needs a selector}"
            shift 2
            ;;
        --fill | --select)
            step "${1#--}" "${2:?$1 needs a selector}" "${3?$1 needs a value}"
            shift 3
            ;;
        --wait)
            step wait "${2:?--wait needs milliseconds}"
            shift 2
            ;;
        --label)
            LABEL="${2:?}"
            shift 2
            ;;
        --full-page)
            FULL_PAGE="true"
            shift
            ;;
        -*) die "unknown option $1 (see --help)" ;;
        /*)
            PATHS+=("$1")
            shift
            ;;
        *) die "a path starts with /: $1" ;;
    esac
done
((${#PATHS[@]})) || die "name at least one path, such as /sources"
[[ -f "$FIXTURES/event-sources.json" ]] || die "no fixtures in $FIXTURES"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
ADMIN_MODULES="$(cd "$TREE/events-admin/node_modules" 2>/dev/null && pwd -P)" ||
    die "$TREE/events-admin/node_modules is missing; symlink the main checkout's"
[[ -d "$REPO_ROOT/events-frontend/node_modules/@playwright/test" ]] ||
    die "$REPO_ROOT/events-frontend/node_modules has no Playwright; symlink the main checkout's"

BASE_SHA="$(git -C "$TREE" rev-parse --verify --quiet "$BASE_REF^{commit}")" || die "unknown ref $BASE_REF"
AFTER_SHA="$(git -C "$TREE" rev-parse HEAD)"
AFTER_NAME="$(git -C "$TREE" symbolic-ref --quiet --short HEAD ||
    git -C "$TREE" branch --remotes --points-at HEAD --format='%(refname:lstrip=3)' | grep -v '^HEAD$' | head -1)"
[[ -n "$AFTER_NAME" ]] || AFTER_NAME="$(git -C "$TREE" rev-parse --short HEAD)"
# A dirty tree is not its commit, so its build is never reused.
AFTER_KEY="$AFTER_SHA"
[[ -z "$(git -C "$TREE" status --porcelain -- events-admin)" ]] || AFTER_KEY="$AFTER_SHA-dirty"

PIDS=()
SERVED=""
BASE_SRC="$OUT/base-src"
cleanup() {
    local pid
    for pid in "${PIDS[@]}"; do kill "$pid" 2>/dev/null || true; done
    if [[ -d "$BASE_SRC" ]]; then
        git -C "$TREE" worktree remove --force "$BASE_SRC" 2>/dev/null || rm -rf "$BASE_SRC"
        git -C "$TREE" worktree prune
    fi
}
trap cleanup EXIT

# build <source checkout> <dist dir>
build() {
    echo "Building $1 into ${2#"$OUT"/}" >&2
    (cd "$1/events-admin" && node "$ADMIN_MODULES/vite/bin/vite.js" build --configLoader native --mode fixtures \
        --outDir "$2" --emptyOutDir --logLevel warn >"$OUT/build.log" 2>&1) ||
        {
            tail -30 "$OUT/build.log" >&2
            die "the build of $1 failed; full log in $OUT/build.log"
        }
}

BEFORE_DIST="$OUT/dist/$BASE_SHA"
AFTER_DIST="$OUT/dist/$AFTER_KEY"
if [[ ! -f "$BEFORE_DIST/index.html" ]]; then
    rm -rf "$BASE_SRC"
    git -C "$TREE" worktree add --quiet --detach "$BASE_SRC" "$BASE_SHA"
    ln -s "$ADMIN_MODULES" "$BASE_SRC/events-admin/node_modules"
    build "$BASE_SRC" "$BEFORE_DIST"
fi
[[ "$AFTER_KEY" != "$AFTER_SHA" || ! -f "$AFTER_DIST/index.html" ]] && build "$TREE" "$AFTER_DIST"

free_port() {
    local port
    while :; do
        port="$(node -e 'const s = require("net").createServer().listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close() })')"
        [[ "$port" != 4173 && "$port" != 5173 && "$port" != 5174 && "$port" != 8080 && "$port" != 8081 ]] && {
            echo "$port"
            return
        }
    done
}

# serve <dist dir> <name>; sets SERVED. Not a command substitution: its subshell would lose the PID for cleanup.
serve() {
    local port
    port="$(free_port)"
    node "$REPO_ROOT/scripts/admin-fixture-server.mjs" "$1" "$FIXTURES" "$port" >"$OUT/server-$2.log" 2>&1 &
    PIDS+=("$!")
    for _ in $(seq 1 50); do
        curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null && {
            SERVED="http://127.0.0.1:$port"
            return
        }
        sleep 0.2
    done
    die "the $2 server did not answer on $port; see $OUT/server-$2.log"
}

serve "$BEFORE_DIST" before
BEFORE_URL="$SERVED"
serve "$AFTER_DIST" after
AFTER_URL="$SERVED"
echo "Serving before ($BASE_REF) on $BEFORE_URL, after ($AFTER_NAME) on $AFTER_URL" >&2

# Node resolves `@playwright/test` from the working directory when the module comes from stdin.
cd "$REPO_ROOT/events-frontend"
OUT="$OUT" BEFORE_URL="$BEFORE_URL" AFTER_URL="$AFTER_URL" PATHS="$(printf '%s\n' "${PATHS[@]}")" STEPS="$STEPS" \
WIDTHS="$WIDTHS" THEMES="$THEMES" LABEL="$LABEL" FULL_PAGE="$FULL_PAGE" \
BASE_REF="$BASE_REF" AFTER_NAME="$AFTER_NAME" REPO_ROOT="$REPO_ROOT" node --input-type=module - <<'EOF'
import { relative } from 'node:path'
import { chromium } from '@playwright/test'

const env = process.env
const list = (value) => value.split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
const steps = env.STEPS.split('\n').filter(Boolean).map((line) => line.split('\t'))
const sides = { before: env.BEFORE_URL, after: env.AFTER_URL }
const slugOf = (path) => path.replace(/^\/+|\/+$/g, '').replace(/[^A-Za-z0-9]+/g, '-') || 'home'
const shown = (file) => {
  const rel = relative(env.REPO_ROOT, file)
  return rel.startsWith('..') ? file : `./${rel}`
}

async function act(page, [kind, selector, value], side, path) {
  if (kind === 'wait') return page.waitForTimeout(Number(selector))
  const target = page.locator(selector).first()
  if ((await target.count()) === 0) {
    console.error(`  ${side} ${path}: no match for --${kind} ${selector}; skipped`)
    return
  }
  if (kind === 'scroll') await target.evaluate((el) => el.scrollIntoView({ block: 'center', inline: 'center' }))
  else if (kind === 'fill') await target.fill(value)
  else if (kind === 'select') await target.selectOption({ label: value })
  else await target.click()
  await page.waitForLoadState('networkidle')
  await page.waitForTimeout(500)
}

const markdown = []
const browser = await chromium.launch()
try {
  for (const path of list(env.PATHS)) {
    const rows = []
    for (const width of list(env.WIDTHS).map(Number)) {
      const phone = width < 768
      const viewport = { width, height: phone ? 844 : 900 }
      for (const theme of list(env.THEMES)) {
        const name = [slugOf(path), env.LABEL, width, theme].filter(Boolean).join('-')
        const cells = []
        for (const [side, origin] of Object.entries(sides)) {
          // Each shot starts from the fixtures as committed.
          await fetch(`${origin}/__reset`, { method: 'POST' })
          const context = await browser.newContext({
            viewport,
            deviceScaleFactor: 2,
            isMobile: phone,
            hasTouch: phone,
            locale: 'en-GB',
            timezoneId: 'Europe/Berlin',
          })
          await context.addInitScript((dark) => {
            const apply = () => document.documentElement?.classList.toggle('dark', dark)
            apply()
            document.addEventListener('DOMContentLoaded', apply)
          }, theme === 'dark')
          const page = await context.newPage()
          await page.goto(origin + path, { waitUntil: 'networkidle' })
          for (const step of steps) await act(page, step, side, path)
          const file = `${env.OUT}/${side}-${name}.png`
          await page.screenshot({ path: file, fullPage: env.FULL_PAGE === 'true' })
          console.error(`  ${shown(file)}`)
          cells.push(`![${side}: ${path} ${[env.LABEL, width, theme].filter(Boolean).join(' ')}](${shown(file)})`)
          await context.close()
        }
        rows.push(`| ${[env.LABEL, `${width}px`, theme].filter(Boolean).join(' · ')} | ${cells.join(' | ')} |`)
      }
    }
    markdown.push(`**\`${path}\`**`, '', `|  | Before (\`${env.BASE_REF}\`) | After (\`${env.AFTER_NAME}\`) |`, '| --- | --- | --- |', ...rows, '')
  }
} finally {
  await browser.close()
}
console.log(markdown.join('\n'))
EOF
