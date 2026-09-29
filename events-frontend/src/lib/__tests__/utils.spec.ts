import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'

import { describe, expect, it } from 'vitest'

import { cn } from '@/lib/utils'

const css = readFileSync(resolve(process.cwd(), 'src/assets/main.css'), 'utf8')

const BUILT_IN = new Set(['sans', 'serif', 'mono'])

function themeTokens(namespace: string): string[] {
  const theme = css.slice(css.indexOf('@theme'), css.indexOf('\n}', css.indexOf('@theme')))
  const names = [...theme.matchAll(new RegExp(`--${namespace}-([a-z-]+):`, 'g'))].map((m) => m[1]!)
  return names.filter(
    (name) => !name.includes('--') && !name.endsWith('-line-height') && !BUILT_IN.has(name),
  )
}

describe('cn', () => {
  const sizes = themeTokens('text')

  it('finds the type scale in main.css', () => {
    expect(sizes).toEqual(['meta', 'body', 'card-title', 'lede', 'section', 'page'])
  })

  it.each(sizes)('keeps text-%s beside a text colour', (size) => {
    expect(cn(`text-${size}`, 'text-muted-foreground')).toBe(`text-${size} text-muted-foreground`)
  })

  it.each(sizes)('lets text-%s override another size', (size) => {
    expect(cn('text-sm', `text-${size}`)).toBe(`text-${size}`)
  })

  it.each(themeTokens('font'))('lets font-%s override another family, not the weight', (family) => {
    expect(cn('font-sans font-bold', `font-${family}`)).toBe(`font-bold font-${family}`)
  })

  it.each(themeTokens('tracking'))('lets tracking-%s override another tracking', (tracking) => {
    expect(cn('tracking-tight', `tracking-${tracking}`)).toBe(`tracking-${tracking}`)
  })
})
