import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/** The manifest is a static file, so these checks stand in for a browser's installability audit (#2727). */

const root = process.cwd()
const read = (path: string) => readFileSync(resolve(root, path))

interface ManifestIcon {
  src: string
  sizes: string
  type: string
  purpose: string
}

const manifest = JSON.parse(read('public/manifest.webmanifest').toString('utf8')) as {
  name: string
  short_name: string
  start_url: string
  scope: string
  display: string
  background_color: string
  theme_color: string
  icons: ManifestIcon[]
}

/** Width and height from a PNG's IHDR chunk, which always follows the 8-byte signature. */
function pngSize(path: string): string {
  const png = read(path)
  expect(png.subarray(1, 4).toString('ascii')).toBe('PNG')
  return `${png.readUInt32BE(16)}x${png.readUInt32BE(20)}`
}

describe('web app manifest', () => {
  it('carries what a browser needs to offer installation', () => {
    expect(manifest.name).toBe('Event Junkie')
    expect(manifest.short_name.length).toBeLessThanOrEqual(12)
    expect(manifest.start_url).toBe('/')
    expect(manifest.scope).toBe('/')
    expect(manifest.display).toBe('standalone')
    expect(manifest.background_color).toMatch(/^#[0-9a-f]{6}$/)
    expect(manifest.theme_color).toMatch(/^#[0-9a-f]{6}$/)
  })

  it('declares icons at 192 and 512 px, one of them maskable, each a PNG of its stated size', () => {
    const sizes = manifest.icons.map((icon) => icon.sizes)
    expect(sizes).toContain('192x192')
    expect(sizes).toContain('512x512')
    expect(manifest.icons.some((icon) => icon.purpose === 'maskable')).toBe(true)
    for (const icon of manifest.icons) {
      expect(icon.type).toBe('image/png')
      expect(pngSize(`public${icon.src}`)).toBe(icon.sizes)
    }
  })

  it('is linked from index.html', () => {
    expect(read('index.html').toString('utf8')).toContain(
      '<link href="/manifest.webmanifest" rel="manifest" />',
    )
  })

  it('is served as application/manifest+json, which the base image does not map', () => {
    const conf = read('docker/nginx.conf').toString('utf8')
    const block = /location = \/manifest\.webmanifest \{([^}]*\}[^}]*)\}/.exec(conf)?.[1] ?? ''
    expect(block).toContain('application/manifest+json webmanifest;')
    expect(block).toContain('no-cache')
  })
})
