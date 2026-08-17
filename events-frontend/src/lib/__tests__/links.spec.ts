import { describe, expect, it } from 'vitest'

import { isReleaseVersion, releaseTagUrl, RELEASES_URL } from '@/lib/links'

/**
 * Version strings this project actually produces, rather than plausible-looking inventions.
 *
 * That distinction is the whole point of #502. The footer used to reject snapshots by testing
 * `version.includes('-SNAPSHOT')`, and its test proved it worked — against `'0.1.0-SNAPSHOT'`, a
 * string that lives only in `gradle.properties` and never reaches a browser. Every deployed build
 * reports something else entirely, so the guard passed its test and failed in production.
 *
 * So these come from `scripts/version.sh compute` and from the tags published to GHCR, not from
 * imagination. Regenerate with:
 *
 *   scripts/version.sh compute refs/heads/main <sha>
 */
const DEPLOYED_SNAPSHOT = '0.1.1-snapshot.20260817180146.g787d7d0' // on staging, 2026-08-17
const LEGACY_SNAPSHOT = '0.1.0-snapshot.gf6407e3' // pre-#455 scheme, still in GHCR
const LOCAL_GRADLE = '0.1.1-SNAPSHOT' // gradle.properties only; never deployed
const NO_BUILD_INFO = 'dev' // the IDE and `bootRun`

const NOT_RELEASES = [DEPLOYED_SNAPSHOT, LEGACY_SNAPSHOT, LOCAL_GRADLE, NO_BUILD_INFO]

describe('isReleaseVersion', () => {
  it.each(['0.1.0', '0.1.1', '1.0.0', '0.1.11', '10.20.30'])('accepts %s', (version) => {
    expect(isReleaseVersion(version)).toBe(true)
  })

  // The four strings this project actually emits that are not releases.
  it.each(NOT_RELEASES)('rejects %s, which this project really produces', (version) => {
    expect(isReleaseVersion(version)).toBe(false)
  })

  // `v0.1.0-rc1` is explicitly unsupported by the version scheme (docs/DEVELOPMENT.md §Versions),
  // but the predicate should not depend on that staying true.
  it.each(['0.1.0-rc1', '0.1.0+build.5', '0.1', '0.1.0.1', 'v0.1.0', ''])(
    'rejects %s',
    (version) => {
      expect(isReleaseVersion(version)).toBe(false)
    },
  )

  it('rejects a missing version rather than throwing', () => {
    expect(isReleaseVersion(null)).toBe(false)
    expect(isReleaseVersion(undefined)).toBe(false)
  })

  it('agrees with the semverFilter production uses to decide the same question', () => {
    // deploy/clusters/production/oci-repository.yaml — the cluster's definition of "a release".
    // Two definitions of one concept drift; this asserts they have not. Compared as a map so a
    // failure names the version rather than an index.
    const semverFilter = /^[0-9]+\.[0-9]+\.[0-9]+$/
    const versions = ['0.1.0', '1.2.3', ...NOT_RELEASES]

    const ours = Object.fromEntries(versions.map((v) => [v, isReleaseVersion(v)]))
    const theirs = Object.fromEntries(versions.map((v) => [v, semverFilter.test(v)]))

    expect(ours).toEqual(theirs)
  })
})

describe('releaseTagUrl', () => {
  it('builds a tag permalink for a released version', () => {
    expect(releaseTagUrl('0.1.0')).toBe(`${RELEASES_URL}/tag/v0.1.0`)
  })

  // The regression. Each of these previously produced a link the Releases page has no entry for:
  // snapshots are published to GHCR and never tagged in git.
  it.each(NOT_RELEASES)('returns null for %s rather than a URL that 404s', (version) => {
    expect(releaseTagUrl(version)).toBeNull()
  })

  it('returns null for a missing version', () => {
    expect(releaseTagUrl(null)).toBeNull()
    expect(releaseTagUrl(undefined)).toBeNull()
  })
})
