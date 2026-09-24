import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

/**
 * §3 of the privacy notice lists every value the site keeps in `localStorage`, and § 25 TDDDG makes
 * that list a legal statement (LEGAL.md §7.4). The compact view added a third key on 2026-09-13
 * while the notice still said "exactly two", and the prose pins could not see it. This reads the
 * keys from the code instead: every `localStorage.setItem(KEY, …)` in `src/`, with `KEY` resolved
 * from its `const` in the same file, must be named in both notices.
 */
const SRC = resolve(process.cwd(), 'src')

function sourceFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    if (statSync(path).isDirectory()) return name === '__tests__' ? [] : sourceFiles(path)
    return /\.(ts|vue)$/.test(name) && !/\.spec\.ts$/.test(name) ? [path] : []
  })
}

function storedKeys(): string[] {
  const keys = new Set<string>()
  for (const file of sourceFiles(SRC)) {
    const text = readFileSync(file, 'utf8')
    for (const [, arg] of text.matchAll(/localStorage\.setItem\(\s*([^,\s]+)/g)) {
      const literal = /^['"`](.+)['"`]$/.exec(arg!)
      const constant = new RegExp(`const ${arg} = ['"\`]([^'"\`]+)['"\`]`).exec(text)
      const key = literal?.[1] ?? constant?.[1]
      if (!key) throw new Error(`${file}: cannot resolve the localStorage key '${arg}'`)
      keys.add(key)
    }
  }
  return [...keys].sort()
}

describe('the privacy notice names every stored key', () => {
  const keys = storedKeys()

  it('finds the keys the code writes', () => {
    expect(keys.length).toBeGreaterThan(0)
  })

  it.each(['de', 'en'])('names each key in PrivacyView.%s.vue', (locale) => {
    const notice = readFileSync(join(SRC, `views/legal/PrivacyView.${locale}.vue`), 'utf8')
    for (const key of keys) expect(notice, `key '${key}'`).toContain(`<code>${key}</code>`)
  })
})
