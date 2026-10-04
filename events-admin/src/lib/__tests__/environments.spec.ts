import { describe, expect, it } from 'vitest'

import { importerTarget } from '../environments'

describe('importerTarget', () => {
  it('maps each environment to the importer forward ej.sh opens', () => {
    expect(importerTarget('staging')).toBe('http://localhost:18081')
    expect(importerTarget('production')).toBe('http://localhost:28081')
  })

  it('refuses a mode that names no cluster', () => {
    expect(() => importerTarget('development')).toThrow('--mode staging or --mode production')
  })
})
