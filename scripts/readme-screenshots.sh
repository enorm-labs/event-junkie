#!/usr/bin/env bash
#
# readme-screenshots.sh — retake the README's screenshots of the events list from a deployed origin:
# desktop and mobile, each in the dark and the light theme.
#
# Usage:
#   scripts/readme-screenshots.sh [origin] [output-dir]   # defaults: https://event-junkie.de, docs/screenshots
#
# Requires: Node and the frontend's Playwright, so `npm ci` and `npx playwright install chromium` in
# events-frontend/ first. Reaches only the origin. Writing into docs/screenshots also sets the "Taken"
# dates in its README and the month in the root README's caption; any other output-dir is a dry run.
#
# FROM PRODUCTION, AFTER A RELEASE. Production has real data and no development overlay; a design
# change reaches it only with a release, so shoot after the release that carries it (#2373).
#
# WHAT THE SCRIPT GETS RIGHT THAT THE HAND RECIPE DID NOT (docs/screenshots/README.md):
# - Dark is a first visit, with no stored theme; light sets `theme=light` before the first paint,
#   because the toggle is the only other way in.
# - The posters below the fold load lazily, so the page scrolls down and back and waits until every
#   image in and just below the frame has settled. A card with no image shows the placeholder on purpose.
# - 2x scale: 1400x900 becomes 2800x1800, and the 390x844 phone 780x1688.
# - The Vue devtools overlay, present only on a dev server, is hidden.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGIN="${1:-https://event-junkie.de}"
ORIGIN="${ORIGIN%/}"
DEFAULT_OUT="$REPO_ROOT/docs/screenshots"
OUT="$(mkdir -p "${2:-$DEFAULT_OUT}" && cd "${2:-$DEFAULT_OUT}" && pwd)"

# Node resolves `@playwright/test` from the working directory when the module comes from stdin.
cd "$REPO_ROOT/events-frontend"
ORIGIN="$ORIGIN" OUT="$OUT" node --input-type=module - <<'EOF'
import { chromium } from '@playwright/test'

const { ORIGIN, OUT } = process.env
const SHOTS = [
  { file: 'events-dark.png', theme: null, viewport: { width: 1400, height: 900 }, mobile: false },
  { file: 'events-light.png', theme: 'light', viewport: { width: 1400, height: 900 }, mobile: false },
  { file: 'events-mobile-dark.png', theme: null, viewport: { width: 390, height: 844 }, mobile: true },
  { file: 'events-mobile-light.png', theme: 'light', viewport: { width: 390, height: 844 }, mobile: true },
]

const browser = await chromium.launch()
try {
  for (const shot of SHOTS) {
    const context = await browser.newContext({
      viewport: shot.viewport,
      deviceScaleFactor: 2,
      isMobile: shot.mobile,
      hasTouch: shot.mobile,
      locale: 'en-GB',
      timezoneId: 'Europe/Berlin',
      colorScheme: 'dark',
    })
    // A fresh context has empty storage, so dark needs nothing; light is stored before any script runs.
    if (shot.theme) {
      await context.addInitScript((theme) => localStorage.setItem('theme', theme), shot.theme)
    }
    const page = await context.newPage()
    await page.goto(`${ORIGIN}/en/events`, { waitUntil: 'networkidle' })
    // Through the CSSOM, which the CSP allows; an injected <style> tag is blocked by `style-src 'self'`.
    await page.evaluate(() => {
      for (const id of ['__vue-devtools-container__', 'vue-inspector-container']) {
        document.getElementById(id)?.style.setProperty('display', 'none', 'important')
      }
    })

    const height = shot.viewport.height
    await page.mouse.wheel(0, height * 2)
    await page.waitForTimeout(500)
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.waitForFunction(
      (limit) =>
        [...document.images]
          .filter((img) => img.getBoundingClientRect().top < limit)
          .every((img) => img.complete),
      height * 1.5,
      { timeout: 30_000 },
    )
    await page.waitForLoadState('networkidle')

    await page.screenshot({ path: `${OUT}/${shot.file}` })
    console.log(`${shot.file}  ${shot.viewport.width}x${shot.viewport.height} @2x`)
    await context.close()
  }
} finally {
  await browser.close()
}
EOF

if [[ "$OUT" == "$DEFAULT_OUT" ]]; then
    today="$(date +%Y-%m-%d)"
    month="$(LC_ALL=C date +'%B %Y')"
    # The "Taken" column of each row that names one of the files, and the caption under the README's pictures.
    sed -E -i.bak "/\`events(-mobile)?-(dark|light)\.png\`/ s/\| [0-9]{4}-[0-9]{2}-[0-9]{2} \|$/| $today |/" "$DEFAULT_OUT/README.md"
    sed -E -i.bak "s/[A-Z][a-z]+ [0-9]{4}; see <a href=\"\.\/docs\/screenshots\/\">/$month; see <a href=\".\/docs\/screenshots\/\">/" "$REPO_ROOT/README.md"
    rm -f "$DEFAULT_OUT/README.md.bak" "$REPO_ROOT/README.md.bak"
    echo "Dates set to $today. Review: git diff --stat docs/screenshots README.md"
fi
