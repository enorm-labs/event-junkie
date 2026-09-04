#!/usr/bin/env node
/**
 * Merges the backend and frontend dependency licence data into one `src/assets/notices.json`,
 * which `/legal/notices` renders. See docs/LEGAL.md §9.
 *
 * Run:
 *   scripts/notices-parity.sh                                  # from the repository root — runs both
 *
 * or the two halves by hand, in this order:
 *
 *   ./gradlew generateLicenseReport --no-configuration-cache   # from the repository root
 *   npm run generate:notices                                   # from events-frontend
 *
 * **This script merges whatever Gradle report is on disk and cannot tell whether it is current.**
 * Running the npm half alone with an old report writes a file that looks entirely correct and is
 * not — on #1073 a report from a month earlier produced 312 components where the answer was 368,
 * and exited 0 (#1084). There is a guard for a *missing* report and there can be no useful guard
 * for a stale one: Gradle skips the task as UP-TO-DATE when the dependency set is unchanged, so the
 * report's age is not evidence of anything. What this script does instead is **say what it merged**,
 * so a month-old backend half is visible in the output rather than silent. Read that line.
 *
 * The output is **committed**. The frontend is not a Gradle subproject (see AGENTS.md), so its
 * build must not have to invoke Gradle; committing the merged file also means the page works in
 * `npm run dev` with nothing else run first, and changes to attribution show up in review as a
 * readable diff.
 *
 * SCOPE, stated honestly because the page says so too:
 * - Runtime/production dependencies only — build and test tooling never reaches a user.
 * - Records each component's name, version, licence and home page. It does **not** reproduce full
 *   licence texts or per-package NOTICE files; those ship with each package, and doing it properly
 *   is the ORT ("Stage 2") upgrade in §9.1.
 */

import { execFileSync } from 'node:child_process'
import { readFileSync, statSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const GRADLE_REPORT = fileURLToPath(
  new URL('../../build/reports/dependency-license/licenses.json', import.meta.url),
)
const OUTPUT = fileURLToPath(new URL('../src/assets/notices.json', import.meta.url))

/** Fold the licence-name spellings each ecosystem emits onto one label per licence. */
const LICENCE_ALIASES = new Map([
  ['Apache License, Version 2.0', 'Apache-2.0'],
  ['The 2-Clause BSD License', 'BSD-2-Clause'],
  ['The 3-Clause BSD License', 'BSD-3-Clause'],
  ['MIT License', 'MIT'],
  ['ISC License', 'ISC'],
  ['BSD Zero Clause License', '0BSD'],
  ['Eclipse Public License - v 2.0', 'EPL-2.0'],
  ['Eclipse Distribution License - v 1.0', 'EDL-1.0'],
  [
    'GNU GENERAL PUBLIC LICENSE, Version 2 + Classpath Exception',
    'GPL-2.0-with-classpath-exception',
  ],
  ['GNU LESSER GENERAL PUBLIC LICENSE, Version 2.1', 'LGPL-2.1'],
  ['Creative Commons Legal Code', 'CC0-1.0'],
  ['PUBLIC DOMAIN', 'Public Domain'],
])

const normaliseLicence = (name) => LICENCE_ALIASES.get(name) ?? name

/** Canonical text for the licences actually in the tree; the page links these per group. */
const LICENCE_URLS = new Map([
  ['Apache-2.0', 'https://www.apache.org/licenses/LICENSE-2.0'],
  ['MIT', 'https://opensource.org/license/mit'],
  ['MIT-0', 'https://opensource.org/license/mit-0'],
  ['ISC', 'https://opensource.org/license/isc-license-txt'],
  ['BSD-2-Clause', 'https://opensource.org/license/bsd-2-clause'],
  ['BSD-3-Clause', 'https://opensource.org/license/bsd-3-clause'],
  ['0BSD', 'https://opensource.org/license/0bsd'],
  ['EPL-2.0', 'https://www.eclipse.org/legal/epl-2.0/'],
  ['EDL-1.0', 'https://www.eclipse.org/org/documents/edl-v10.php'],
  ['MPL-2.0', 'https://www.mozilla.org/MPL/2.0/'],
  ['LGPL-2.1', 'https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html'],
  ['GPL-2.0-with-classpath-exception', 'https://openjdk.org/legal/gplv2+ce.html'],
  ['CC0-1.0', 'https://creativecommons.org/publicdomain/zero/1.0/'],
  ['CC-BY-4.0', 'https://creativecommons.org/licenses/by/4.0/'],
  ['BlueOak-1.0.0', 'https://blueoakcouncil.org/license/1.0.0'],
  ['OFL-1.1', 'https://openfontlicense.org/'],
])

function readBackendComponents() {
  let raw
  try {
    raw = readFileSync(GRADLE_REPORT, 'utf8')
  } catch {
    throw new Error(
      `Backend licence report not found at ${GRADLE_REPORT}.\n` +
        'Run this first, from the repository root:\n' +
        '  ./gradlew generateLicenseReport --no-configuration-cache',
    )
  }

  return JSON.parse(raw).dependencies.map((dependency) => {
    // A module can list several licences (dual licensing). Keep them all: which one we rely on is
    // a legal choice, and silently picking the first would misattribute.
    const licences = (dependency.moduleLicenses ?? [])
      .map((entry) => entry.moduleLicense)
      .filter(Boolean)
      .map(normaliseLicence)

    return {
      name: dependency.moduleName,
      version: dependency.moduleVersion ?? null,
      licenses: [...new Set(licences)].sort(),
      url: dependency.moduleUrls?.[0] ?? null,
      ecosystem: 'backend',
    }
  })
}

/**
 * Platform-specific optional binaries, which are installed only for the host that runs `npm ci`.
 *
 * They must be excluded, and the reason is not tidiness — it is **determinism**. `license-checker`
 * walks the *installed* tree, so leaving them in makes the generated file depend on the machine
 * that generated it: `@esbuild/darwin-arm64` on a Mac, `@esbuild/linux-x64` on CI. That breaks this
 * script's own contract (unchanged dependencies produce an identical file) and, more importantly,
 * makes a "regenerate and fail on a non-empty diff" CI check impossible — it would fail every run
 * for a reason that has nothing to do with attribution.
 *
 * Dropping them is also the honest answer for a notices page: these are build-time binaries for one
 * CPU architecture. None of them is shipped to a browser, so none of them is distributed, so none
 * of them needs attributing here. The cross-platform package that pulls them in (`esbuild` itself)
 * stays listed.
 *
 * **The name pattern below is necessary and not sufficient**, which cost a day to find out. It
 * matches packages whose *name* carries a platform, like `@esbuild/darwin-arm64`. It cannot match
 * `fsevents` — a macOS-only native binding with an ordinary name, declaring its restriction in the
 * `os` field of its own package.json instead. So a Mac generated a file with one extra component
 * and CI rejected it, which is exactly the non-determinism this guard exists to prevent, arriving
 * through the door it did not cover. `excludedByOs` below closes that, using the field rather than
 * the name so the next such package needs no new pattern.
 */
const PLATFORM_SPECIFIC =
  /[-/](darwin|linux|win32|freebsd|openbsd|android|sunos)-(x64|arm64|arm|ia32|ppc64|ppc64le|s390x|riscv64|loong64)(-(gnu|musl|msvc|eabi|eabihf))?@/

/**
 * True when a package declares an `os` that excludes the platform we deploy on.
 *
 * `linux` is not a preference here: both images are Linux, on amd64 and arm64, so a package npm
 * would refuse to install there cannot reach a user by any route. `cpu` is deliberately *not*
 * checked — we publish both architectures, so an arch-restricted package does ship on one of them.
 *
 * Reads the installed package.json via the `path` license-checker reports. A package that cannot be
 * read is kept: over-reporting a notice is a smaller error than dropping one, and this is a legal
 * document. See docs/LEGAL.md §9.
 */
function excludedByOs(info) {
  if (!info?.path) return false
  try {
    const { os } = JSON.parse(readFileSync(`${info.path}/package.json`, 'utf8'))
    if (!Array.isArray(os) || os.length === 0) return false
    // npm's own semantics: a leading `!` negates, so `["!win32"]` means everywhere but Windows.
    if (os.some((value) => value.startsWith('!'))) return os.includes('!linux')
    return !os.includes('linux')
  } catch {
    return false
  }
}

function readFrontendComponents() {
  // `--production` drops devDependencies; `--excludePrivatePackages` drops this app itself.
  const stdout = execFileSync(
    'npx',
    ['license-checker-rseidelsohn', '--production', '--json', '--excludePrivatePackages'],
    {
      cwd: fileURLToPath(new URL('..', import.meta.url)),
      encoding: 'utf8',
      maxBuffer: 64 * 1024 * 1024,
    },
  )

  return Object.entries(JSON.parse(stdout))
    .filter(([id, info]) => !PLATFORM_SPECIFIC.test(id) && !excludedByOs(info))
    .map(([id, info]) => {
      // license-checker keys are `name@version`; the name itself may contain `@` (scoped packages).
      const at = id.lastIndexOf('@')
      const raw = Array.isArray(info.licenses) ? info.licenses : [info.licenses]
      const licences = raw
        .filter(Boolean)
        // "(MIT OR Apache-2.0)" is one dual-licence string, not two components.
        .flatMap((value) => value.replace(/^\(|\)$/g, '').split(/ OR | AND /))
        .map((value) => normaliseLicence(value.trim()))

      return {
        name: id.slice(0, at),
        version: id.slice(at + 1) || null,
        licenses: [...new Set(licences)].sort(),
        url: info.repository ?? null,
        ecosystem: 'frontend',
      }
    })
}

const components = [...readBackendComponents(), ...readFrontendComponents()].sort((a, b) =>
  a.name.localeCompare(b.name),
)

// Group by the licence label so the page shows each licence once with its components beneath.
// A dual-licensed component appears under each of its licences — that is accurate, not a bug.
const groups = new Map()
for (const component of components) {
  const licences = component.licenses.length > 0 ? component.licenses : ['Unknown']
  for (const licence of licences) {
    if (!groups.has(licence)) groups.set(licence, [])
    groups.get(licence).push({
      name: component.name,
      version: component.version,
      url: component.url,
      ecosystem: component.ecosystem,
    })
  }
}

const notices = {
  // Deliberately no `generatedAt`: a timestamp would make every regeneration a diff even when the
  // dependency set is unchanged, which is noise in review and a false signal of movement.
  componentCount: components.length,
  licenses: [...groups.entries()]
    .map(([license, comps]) => ({
      license,
      url: LICENCE_URLS.get(license) ?? null,
      components: comps.sort((a, b) => a.name.localeCompare(b.name)),
    }))
    // Most-used licence first; it is what a reader is most likely looking for.
    .sort(
      (a, b) => b.components.length - a.components.length || a.license.localeCompare(b.license),
    ),
}

writeFileSync(OUTPUT, `${JSON.stringify(notices, null, 2)}\n`)

const unknown = notices.licenses.find((group) => group.license === 'Unknown')
const backendCount = components.filter((component) => component.ecosystem === 'backend').length

// **Naming the Gradle report and its date is the point of this line**, not decoration. The backend
// half comes from a file this script did not produce and cannot validate, so the one defence against
// merging a stale one is showing which file was read and when it was written. A count that drops by
// fifty is obvious here and invisible in the JSON.
console.log(
  `Wrote ${OUTPUT}\n` +
    `  ${notices.componentCount} components (${backendCount} backend, ` +
    `${notices.componentCount - backendCount} frontend), ${notices.licenses.length} licences\n` +
    `  backend half from ${GRADLE_REPORT}\n` +
    `    written ${statSync(GRADLE_REPORT).mtime.toISOString()} — regenerate it if that looks old` +
    (unknown
      ? `\n  WARNING: ${unknown.components.length} component(s) with no licence metadata`
      : ''),
)
