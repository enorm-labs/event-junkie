import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const page = (locale: 'en' | 'de') =>
  readFileSync(resolve(__dirname, `../AboutView.${locale}.md`), 'utf8')

const tocTargets = (source: string) =>
  [...source.matchAll(/:to="\{ hash: '#([\w-]+)' \}"/g)].map((match) => match[1])

const sectionIds = (source: string) =>
  [...source.matchAll(/<section id="([\w-]+)"/g)].map((match) => match[1])

describe('About page table of contents', () => {
  it.each(['en', 'de'] as const)('links every section of the %s page, in page order', (locale) => {
    const source = page(locale)

    expect(tocTargets(source)).toEqual(sectionIds(source))
    expect(sectionIds(source)).toHaveLength(7)
  })

  it('uses the same anchors in both languages, so a link works whichever locale opens it', () => {
    expect(sectionIds(page('de'))).toEqual(sectionIds(page('en')))
  })
})
