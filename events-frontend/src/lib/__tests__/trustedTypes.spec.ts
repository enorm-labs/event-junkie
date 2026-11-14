import { afterEach, describe, expect, it, vi } from 'vitest'

type Rules = {
  createHTML?: (s: string) => unknown
  createScript?: (s: string) => unknown
  createScriptURL?: (s: string) => unknown
}

function stubTrustedTypes() {
  const policies = new Map<string, Rules>()
  vi.stubGlobal('trustedTypes', {
    createPolicy: (name: string, rules: Rules) => {
      policies.set(name, rules)
      return rules
    },
  })
  return policies
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.resetModules()
})

describe('trustedTypes', () => {
  it('passes JSON through the json-ld policy and refuses anything else', async () => {
    const policies = stubTrustedTypes()
    const { jsonLdScript } = await import('@/lib/trustedTypes')
    expect(jsonLdScript('{"@type":"Event"}')).toBe('{"@type":"Event"}')
    expect(() => policies.get('json-ld')!.createScript!('alert(1)')).toThrow(SyntaxError)
  })

  it('lets the default policy vouch for the map worker and its attribution, and nothing else', async () => {
    const policies = stubTrustedTypes()
    const { allowMapStrings } = await import('@/lib/trustedTypes')
    allowMapStrings('/assets/maplibre-gl-worker-abc.js', '<a href="https://osm.org">© OSM</a>')
    const rules = policies.get('default')!
    expect(rules.createScriptURL!(`${location.origin}/assets/maplibre-gl-worker-abc.js`)).toBe(
      `${location.origin}/assets/maplibre-gl-worker-abc.js`,
    )
    expect(() => rules.createScriptURL!('https://evil.example/x.js')).toThrow(
      'Script URL not allowed',
    )
    expect(rules.createHTML!('<a href="https://osm.org">© OSM</a>')).toBe(
      '<a href="https://osm.org">© OSM</a>',
    )
    expect(() => rules.createHTML!('<img src=x onerror=alert(1)>')).toThrow('HTML not allowed')
    expect(rules.createScript).toBeUndefined()
  })

  it('hands back the plain string where the browser has no Trusted Types', async () => {
    const { allowMapStrings, jsonLdScript } = await import('@/lib/trustedTypes')
    expect(jsonLdScript('{}')).toBe('{}')
    expect(() => allowMapStrings('/assets/w.js', '© OSM')).not.toThrow()
  })
})
