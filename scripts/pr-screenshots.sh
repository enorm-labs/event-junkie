#!/usr/bin/env bash
#
# pr-screenshots.sh — before and after screenshots of frontend pages for a pull request: the base ref against the branch,
# each built and served on a free port with production's API behind it.
#
# Usage:
#   scripts/pr-screenshots.sh [options] <path>...     # paths such as / or /en/events/<slug>
#     --base <ref>           before side (default origin/main)
#     --tree <dir>           after side: a checkout of the branch (default this one)
#     --widths 390,1280      viewport widths; a width under 768 is a phone
#     --themes light,dark    dark is what a first visit sees
#     --views poster         poster, compact or both: the events list's view setting
#     --click <selector>     click the first match before the shot, to open a menu or a popover
#     --scroll <selector>    scroll the first match to the middle of the frame before the shot
#     --focus <selector>     focus the first match as the keyboard would, so its focus-visible ring shows
#     --label <name>         added to the file names, so a second run with --click does not overwrite the first
#     --full-page            the whole page instead of the viewport
#     --out <dir>            default build/pr-screenshots
#   Then, from the repository root: gh pr edit <n> --body-file <body.md> --attach <png>...
#
# Requires: Node, git and the frontend's node_modules with Playwright's Chromium (`npx playwright install chromium`).
# Reaches https://event-junkie.de through each preview server's /api and /map/ proxy, as a visitor's browser does.
# Writes nothing outside --out: the base ref is checked out with `git worktree add --detach` under it and removed
# on exit. A built dist/ is kept per commit, so a second run for the same pair builds nothing.
#
# WHY A PREVIEW BUILD AND NOT THE DEV SERVER: ports 5173, 4173, 8080 and 8081 belong to whichever checkout started
# them first, so a worktree that uses them shoots someone else's code. A dist/ on a port of its own is the change.
#
# THEME: index.html makes dark the default unless localStorage says `light`; emulateMedia changes nothing. Each
# context stores the theme and the view before the first script runs. Viewport shots by default, because MapLibre
# draws a strip in a full-page one.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGIN="https://event-junkie.de"
BASE_REF="origin/main"
TREE="$REPO_ROOT"
OUT="$REPO_ROOT/build/pr-screenshots"
WIDTHS="390,1280"
THEMES="light,dark"
VIEWS="poster"
CLICK=""
SCROLL=""
FOCUS=""
LABEL=""
FULL_PAGE="false"
PATHS=()

die() {
    echo "pr-screenshots: $*" >&2
    exit 1
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
        --views)
            VIEWS="${2:?}"
            shift 2
            ;;
        --click)
            CLICK="${2:?}"
            shift 2
            ;;
        --scroll)
            SCROLL="${2:?}"
            shift 2
            ;;
        --focus)
            FOCUS="${2:?}"
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
((${#PATHS[@]})) || die "name at least one path, such as /en/events"

mkdir -p "$OUT"
OUT="$(cd "$OUT" && pwd)"
NODE_MODULES="$(cd "$TREE/events-frontend/node_modules" 2>/dev/null && pwd -P)" ||
    die "$TREE/events-frontend/node_modules is missing; symlink the main checkout's"

BASE_SHA="$(git -C "$TREE" rev-parse --verify --quiet "$BASE_REF^{commit}")" || die "unknown ref $BASE_REF"
AFTER_SHA="$(git -C "$TREE" rev-parse HEAD)"
# A detached checkout of a pull request's branch is named after the remote branch at its commit.
AFTER_NAME="$(git -C "$TREE" symbolic-ref --quiet --short HEAD ||
    git -C "$TREE" branch --remotes --points-at HEAD --format='%(refname:lstrip=3)' | grep -v '^HEAD$' | head -1)"
[[ -n "$AFTER_NAME" ]] || AFTER_NAME="$(git -C "$TREE" rev-parse --short HEAD)"
# A dirty tree is not its commit, so its build is never reused.
AFTER_KEY="$AFTER_SHA"
[[ -z "$(git -C "$TREE" status --porcelain -- events-frontend)" ]] || AFTER_KEY="$AFTER_SHA-dirty"

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
    (cd "$1/events-frontend" && node "$NODE_MODULES/vite/bin/vite.js" build --configLoader native \
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
    ln -s "$NODE_MODULES" "$BASE_SRC/events-frontend/node_modules"
    build "$BASE_SRC" "$BEFORE_DIST"
fi
[[ "$AFTER_KEY" != "$AFTER_SHA" || ! -f "$AFTER_DIST/index.html" ]] && build "$TREE" "$AFTER_DIST"

# Plain object, no imports: it lives outside the frontend, where no package resolves.
cat >"$OUT/preview.config.mjs" <<EOF
const target = { target: '$ORIGIN', changeOrigin: true }
export default { preview: { proxy: { '/api': target, '^/map/': target } } }
EOF

free_port() {
    local port
    while :; do
        port="$(node -e 'const s = require("net").createServer().listen(0, "127.0.0.1", () => { console.log(s.address().port); s.close() })')"
        [[ "$port" != 4173 && "$port" != 5173 && "$port" != 8080 && "$port" != 8081 ]] && {
            echo "$port"
            return
        }
    done
}

# serve <dist dir> <name>; sets SERVED. Not a command substitution: its subshell would lose the PID for cleanup.
serve() {
    local port
    port="$(free_port)"
    (cd "$OUT" && exec node "$NODE_MODULES/vite/bin/vite.js" preview --config "$OUT/preview.config.mjs" \
        --outDir "$1" --host 127.0.0.1 --port "$port" --strictPort >"$OUT/preview-$2.log" 2>&1) &
    PIDS+=("$!")
    for _ in $(seq 1 50); do
        curl -fsS -o /dev/null "http://127.0.0.1:$port/" 2>/dev/null && {
            SERVED="http://127.0.0.1:$port"
            return
        }
        sleep 0.2
    done
    die "the $2 preview did not answer on $port; see $OUT/preview-$2.log"
}

serve "$BEFORE_DIST" before
BEFORE_URL="$SERVED"
serve "$AFTER_DIST" after
AFTER_URL="$SERVED"
echo "Serving before ($BASE_REF) on $BEFORE_URL, after ($AFTER_NAME) on $AFTER_URL" >&2

# Node resolves `@playwright/test` from the working directory when the module comes from stdin.
cd "$TREE/events-frontend"
OUT="$OUT" BEFORE_URL="$BEFORE_URL" AFTER_URL="$AFTER_URL" PATHS="$(printf '%s\n' "${PATHS[@]}")" \
WIDTHS="$WIDTHS" THEMES="$THEMES" VIEWS="$VIEWS" CLICK="$CLICK" SCROLL="$SCROLL" FOCUS="$FOCUS" LABEL="$LABEL" FULL_PAGE="$FULL_PAGE" \
BASE_REF="$BASE_REF" AFTER_NAME="$AFTER_NAME" REPO_ROOT="$REPO_ROOT" node --input-type=module - <<'EOF'
import { relative } from 'node:path'
import { chromium } from '@playwright/test'

const env = process.env
const list = (value) => value.split(/[,\n]/).map((s) => s.trim()).filter(Boolean)
const sides = { before: env.BEFORE_URL, after: env.AFTER_URL }
const slugOf = (path) => path.replace(/^\/+|\/+$/g, '').replace(/[^A-Za-z0-9]+/g, '-') || 'home'
const shown = (file) => {
  const rel = relative(env.REPO_ROOT, file)
  return rel.startsWith('..') ? file : `./${rel}`
}

// Waits for the posters in and just below the frame; they load lazily.
async function settle(page, height) {
  await page.waitForLoadState('networkidle')
  await page
    .waitForFunction(
      (limit) => [...document.images].filter((img) => img.getBoundingClientRect().top < limit).every((img) => img.complete),
      height * 1.5,
      { timeout: 15_000 },
    )
    .catch(() => console.error('  some images did not finish loading'))
}

async function act(page, kind, selector, side, path) {
  const target = page.locator(selector).first()
  if ((await target.count()) === 0) {
    console.error(`  ${side} ${path}: no match for --${kind} ${selector}; shot without it`)
    return
  }
  if (kind === 'scroll') await target.evaluate((el) => el.scrollIntoView({ block: 'center' }))
  else if (kind === 'focus') {
    // A key press first makes Chromium treat the scripted focus as keyboard focus.
    await page.keyboard.press('Shift')
    await target.focus()
  } else await target.click()
  await page.waitForTimeout(400)
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
        for (const view of list(env.VIEWS)) {
          const name = [slugOf(path), env.LABEL, width, theme, view === 'poster' ? '' : view].filter(Boolean).join('-')
          const cells = []
          for (const [side, origin] of Object.entries(sides)) {
            const context = await browser.newContext({
              viewport,
              deviceScaleFactor: 2,
              isMobile: phone,
              hasTouch: phone,
              locale: 'en-GB',
              timezoneId: 'Europe/Berlin',
            })
            await context.addInitScript(
              ([t, v]) => {
                localStorage.setItem('theme', t)
                localStorage.setItem('view', v)
              },
              [theme, view],
            )
            const page = await context.newPage()
            await page.goto(origin + path, { waitUntil: 'networkidle' })
            await settle(page, viewport.height)
            if (env.SCROLL) await act(page, 'scroll', env.SCROLL, side, path)
            if (env.CLICK) await act(page, 'click', env.CLICK, side, path)
            if (env.SCROLL || env.CLICK) await settle(page, viewport.height)
            if (env.FOCUS) await act(page, 'focus', env.FOCUS, side, path)
            const file = `${env.OUT}/${side}-${name}.png`
            await page.screenshot({ path: file, fullPage: env.FULL_PAGE === 'true' })
            console.error(`  ${shown(file)}`)
            cells.push(`![${side}: ${path} ${[env.LABEL, width, theme, view === 'poster' ? '' : view].filter(Boolean).join(' ')}](${shown(file)})`)
            await context.close()
          }
          rows.push(`| ${[env.LABEL, `${width}px`, theme, view === 'poster' ? '' : view].filter(Boolean).join(' · ')} | ${cells.join(' | ')} |`)
        }
      }
    }
    markdown.push(`**\`${path}\`**`, '', `|  | Before (\`${env.BASE_REF}\`) | After (\`${env.AFTER_NAME}\`) |`, '| --- | --- | --- |', ...rows, '')
  }
} finally {
  await browser.close()
}
console.log(markdown.join('\n'))
EOF
