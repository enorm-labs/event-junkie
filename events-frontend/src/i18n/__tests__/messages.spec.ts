import { readdirSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { DEFAULT_LOCALE, LOCALES } from '@/i18n/locales'

/**
 * Guards the message catalogues against the failures that ship silently: a missing key falls
 * back to the default locale, so a half-translated language looks fine until a German reader
 * hits an English sentence, and an extra key is dead weight.
 *
 * Reads the JSON from disk rather than importing it: `@intlify/unplugin-vue-i18n` precompiles
 * any interpolated message into an AST whose shape follows the sentence, so importing compares
 * build artefacts and reports phantom differences like `errors.connection.body.items.0.value`.
 */

// `process.cwd()` rather than `import.meta.url`, which is an http: URL under vitest's jsdom
// transform.
const MESSAGES_DIR = resolve(process.cwd(), 'src/i18n/messages')

/** The authored catalogue for `locale`, merged from its namespace files. */
function catalogue(locale: string): Record<string, unknown> {
  const dir = `${MESSAGES_DIR}/${locale}`
  const merged: Record<string, unknown> = {}
  for (const file of readdirSync(dir)
    .filter((name) => name.endsWith('.json'))
    .sort()) {
    merged[file.replace(/\.json$/, '')] = JSON.parse(readFileSync(`${dir}/${file}`, 'utf8'))
  }
  return merged
}

/** All leaf key paths, e.g. `common.nav.events`. */
function keyPaths(messages: object, prefix = ''): string[] {
  return Object.entries(messages).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return typeof value === 'object' && value !== null ? keyPaths(value, path) : [path]
  })
}

function valueAt(messages: object, path: string): unknown {
  return path
    .split('.')
    .reduce<unknown>((node, key) => (node as Record<string, unknown>)?.[key], messages)
}

const catalogues = Object.fromEntries(LOCALES.map((locale) => [locale, catalogue(locale)]))

describe('message catalogues', () => {
  it('has a catalogue for every published locale', () => {
    // The one that bites: adding a locale to LOCALES makes its URLs routable immediately, so a
    // missing catalogue renders the fallback under a foreign URL.
    for (const locale of LOCALES) {
      expect(Object.keys(catalogues[locale] ?? {}), `no catalogue for "${locale}"`).not.toEqual([])
    }
  })

  it('gives every locale the same keys as the fallback', () => {
    const reference = keyPaths(catalogues[DEFAULT_LOCALE]!).sort()

    for (const [locale, messages] of Object.entries(catalogues)) {
      const actual = keyPaths(messages).sort()
      expect(
        reference.filter((k) => !actual.includes(k)),
        `"${locale}" is missing keys`,
      ).toEqual([])
      expect(
        actual.filter((k) => !reference.includes(k)),
        `"${locale}" has extra keys`,
      ).toEqual([])
    }
  })

  it('has no empty strings, which render as a blank space rather than an error', () => {
    for (const [locale, messages] of Object.entries(catalogues)) {
      const empties = keyPaths(messages).filter((path) => {
        const value = valueAt(messages, path)
        return typeof value === 'string' && value.trim() === ''
      })
      expect(empties, `"${locale}" has empty messages`).toEqual([])
    }
  })

  it('keeps error subjects lowercase, so they read inside the sentence that carries them', () => {
    for (const [locale, messages] of Object.entries(catalogues)) {
      const capitalised = keyPaths(messages)
        .filter((path) => path.startsWith('errors.subject.'))
        .filter((path) => {
          const value = valueAt(messages, path)
          return typeof value === 'string' && value[0] !== value[0]?.toLowerCase()
        })
      expect(
        capitalised,
        `"${locale}" has error subjects that read as stand-alone phrases (#772)`,
      ).toEqual([])
    }
  })

  it('keeps named interpolations consistent across locales', () => {
    // `{subject}` in one language and `{thing}` in another renders the literal placeholder.
    const placeholders = (messages: object, path: string) => {
      const value = valueAt(messages, path)
      return typeof value === 'string'
        ? [...value.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()
        : []
    }

    for (const [locale, messages] of Object.entries(catalogues)) {
      for (const path of keyPaths(catalogues[DEFAULT_LOCALE]!)) {
        expect(placeholders(messages, path), `"${locale}" at "${path}"`).toEqual(
          placeholders(catalogues[DEFAULT_LOCALE]!, path),
        )
      }
    }
  })

  it('does not leave a locale untranslated by copying the fallback verbatim', () => {
    // A catalogue copied from `en/` and left untranslated would pass every check above. Proper
    // nouns and loanwords legitimately match, so this asserts on the proportion.
    const reference = catalogues[DEFAULT_LOCALE]!
    const paths = keyPaths(reference)

    for (const [locale, messages] of Object.entries(catalogues)) {
      if (locale === DEFAULT_LOCALE) continue
      const identical = paths.filter((path) => valueAt(messages, path) === valueAt(reference, path))
      const share = identical.length / paths.length
      expect(
        share,
        `"${locale}" is ${Math.round(share * 100)}% identical to "${DEFAULT_LOCALE}"`,
      ).toBeLessThan(0.5)
    }
  })
})
