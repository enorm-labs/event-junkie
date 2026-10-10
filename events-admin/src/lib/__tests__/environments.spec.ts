import { describe, expect, it } from 'vitest'

import { importerTarget, publicOrigin } from '../environments'

describe('importerTarget', () => {
  it('maps each environment to the importer forward ej.sh opens', () => {
    expect(importerTarget('staging')).toBe('http://localhost:18081')
    expect(importerTarget('production')).toBe('http://localhost:28081')
  })

  it('refuses a mode that names no cluster', () => {
    expect(() => importerTarget('development')).toThrow('--mode staging or --mode production')
  })
})

describe('publicOrigin', () => {
  it('links the public site of the cluster behind the forward', () => {
    expect(publicOrigin('staging')).toBe('https://staging.event-junkie.de')
    expect(publicOrigin('production')).toBe('https://event-junkie.de')
  })

  it('links production from a build without a cluster', () => {
    expect(publicOrigin('fixtures')).toBe('https://event-junkie.de')
  })
})
