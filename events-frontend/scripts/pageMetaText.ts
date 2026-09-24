import { readFileSync } from 'node:fs'
import type { Plugin } from 'vite'

// Explicit `.ts`: this module is reached from vite.config.ts, which Vite's `configLoader: 'native'`
// mode loads through Node's ESM resolver. See the note in vite.config.ts.
import { LOCALES } from '../src/i18n/locales.ts'

const ID = 'virtual:page-meta-text'
const RESOLVED_ID = `\0${ID}`
const FILES = ['pageTitle', 'pageDescription'] as const

/**
 * Serves the page titles and descriptions as plain strings, as `virtual:page-meta-text`.
 *
 * The vue-i18n plugin compiles every file under `src/i18n/messages/` into a message AST, `?raw`
 * imports included, and the injector build has no such plugin. So a direct import reads an AST in
 * the app and in Vitest, and JSON in the injector. This module reads the same two files with `fs`
 * and gives every build the same strings, which the injector's parity test relies on (#1911).
 */
export function pageMetaText(): Plugin {
  const source = (locale: string, file: string) =>
    new URL(`../src/i18n/messages/${locale}/${file}.json`, import.meta.url)

  return {
    name: 'event-junkie:page-meta-text',

    resolveId(id) {
      return id === ID ? RESOLVED_ID : undefined
    },

    load(id) {
      if (id !== RESOLVED_ID) return undefined
      const text = Object.fromEntries(
        LOCALES.map((locale) => [
          locale,
          Object.fromEntries(
            FILES.map((file) => {
              this.addWatchFile(source(locale, file).pathname)
              return [file, JSON.parse(readFileSync(source(locale, file), 'utf8')) as unknown]
            }),
          ),
        ]),
      )
      return `export default ${JSON.stringify(text)}`
    },
  }
}
