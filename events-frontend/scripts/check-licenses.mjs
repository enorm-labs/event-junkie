#!/usr/bin/env node
/**
 * Fails if any production npm dependency carries a licence outside
 * `config/allowed-licenses-npm.json`.
 *
 * This is the frontend counterpart to `./gradlew checkLicense`, which only ever sees the JVM tree
 * — the frontend is not a Gradle subproject. Without it, npm licences were audited only at PR time
 * by the deny-list in dependency-review.yml, which sees *newly introduced* dependencies and so
 * never looked at the several hundred already in the tree.
 *
 * Not `license-checker-rseidelsohn --onlyAllow`: that matches against the whole licence field as a
 * string, so a dual licence like "(MIT OR CC0-1.0)" has to be allow-listed verbatim, and its
 * failure output does not say which package is at fault. Reimplementing the comparison is ~30
 * lines and lets a package pass on ANY of its licences, matching the Gradle plugin's semantics.
 *
 * Run: npm run check:licenses
 */

import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const POLICY = fileURLToPath(new URL('../../config/allowed-licenses-npm.json', import.meta.url))
const FRONTEND = fileURLToPath(new URL('..', import.meta.url))

const allowed = new Set(JSON.parse(readFileSync(POLICY, 'utf8')).allowedLicenses)

const packages = JSON.parse(
  execFileSync(
    'npx',
    // The same flags as generate-notices.mjs, so the check and the notices look at one set.
    ['license-checker-rseidelsohn', '--production', '--nopeer', '--json', '--excludePrivatePackages'],
    { cwd: FRONTEND, encoding: 'utf8', maxBuffer: 64 * 1024 * 1024 },
  ),
)

/** "(MIT OR CC0-1.0)" and "Apache-2.0 AND MIT" both describe one package with several licences. */
function licensesOf(info) {
  const raw = Array.isArray(info.licenses) ? info.licenses : [info.licenses]
  return raw
    .filter(Boolean)
    .flatMap((value) =>
      String(value)
        .replace(/^\(|\)$/g, '')
        .split(/ OR | AND /),
    )
    .map((value) => value.trim())
}

const violations = []
for (const [id, info] of Object.entries(packages)) {
  const licenses = licensesOf(info)
  // No metadata at all is a violation, not a pass: an unknown licence is the case most worth
  // stopping for (docs/LEGAL.md §9.2).
  if (licenses.length === 0) {
    violations.push({ id, licenses: ['<none declared>'] })
  } else if (!licenses.some((license) => allowed.has(license))) {
    violations.push({ id, licenses })
  }
}

const checked = Object.keys(packages).length

if (violations.length > 0) {
  console.error(
    `\n${violations.length} of ${checked} production packages have a disallowed licence:\n`,
  )
  for (const { id, licenses } of violations) {
    console.error(`  ${id}\n    ${licenses.join(', ')}`)
  }
  console.error(
    '\nDo not widen config/allowed-licenses-npm.json just to make this pass.\n' +
      'AGPL, GPL without the Classpath Exception, and source-available licences (SSPL, BUSL,\n' +
      'Elastic-2.0) are not acceptable for a public network service — see\n' +
      'docs/LEGAL.md §9.2. Replace the dependency, or record the reasoning in\n' +
      "the policy file's `_rationale` if it genuinely belongs on the list.\n",
  )
  process.exit(1)
}

console.log(`All ${checked} production packages carry an allowed licence.`)
