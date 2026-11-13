/**
 * The two Trusted Types policies the site makes besides Vue's (#4955). The CSP names `vue json-ld default` and refuses
 * a raw string at every other DOM sink. Neither policy passes text through unchecked. Where the browser has no
 * Trusted Types, each helper hands back the plain string.
 */

type Policy = {
  createScript?: (input: string) => unknown
  createScriptURL?: (input: string) => unknown
}
type PolicyFactory = { createPolicy: (name: string, rules: Policy) => Policy }

function factory(): PolicyFactory | undefined {
  return (globalThis as unknown as { trustedTypes?: PolicyFactory }).trustedTypes
}

let jsonLd: Policy | null | undefined

/** JSON-LD for a `<script type="application/ld+json">`: only text that parses as JSON passes. */
export function jsonLdScript(text: string): string {
  jsonLd ??=
    factory()?.createPolicy('json-ld', {
      createScript: (input) => {
        JSON.parse(input)
        return input
      },
    }) ?? null
  // A TrustedScript stands where the DOM types say string; the sink accepts either.
  return (jsonLd?.createScript?.(text) as string | undefined) ?? text
}

let defaultMade = false

/**
 * Lets MapLibre start its worker from [url] and from nothing else. MapLibre passes the URL as a string, so only the
 * `default` policy can vouch for it; every other string at a script-URL sink still throws.
 */
export function allowWorkerUrl(url: string): void {
  const tt = factory()
  if (!tt || defaultMade) return
  const allowed = new URL(url, location.href).href
  tt.createPolicy('default', {
    createScriptURL: (input) => {
      if (new URL(input, location.href).href !== allowed)
        throw new TypeError(`Script URL not allowed: ${input}`)
      return input
    },
  })
  defaultMade = true
}
