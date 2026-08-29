/**
 * The Content-Security-Policy, minus the one directive that depends on the deployment.
 *
 * **This is the second of two copies, and `scripts/csp-parity.sh` is what stops them drifting.** A
 * visitor gets the header from Traefik, so the copy that reaches production is
 * `deploy/charts/event-junkie/values.yaml`. This one exists so `npm run preview` — the server the
 * Playwright suite runs against on CI — serves the built site under the same rules. Without it the
 * suite passes whatever the policy says, and the first evidence of a wrong one is a blank page on
 * staging (#846).
 *
 * **`img-src` is deliberately absent**, because it is the one directive neither side can state on
 * its own. The chart derives it from `images.serving.enabled`: while serving is off the API hands
 * out the venue's own URL, so `'self'` would blank every image on the site. See the values file.
 */
export const CSP_DIRECTIVES = [
  "default-src 'self'",
  // A relative URL in the SPA must keep meaning what it says, and nothing here is meant to be
  // embedded. `frame-ancestors` says what the middleware's `frameDeny` says, to browsers that
  // stopped reading X-Frame-Options.
  "base-uri 'self'",
  "object-src 'none'",
  "frame-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  // The hash is of the theme script in index.html, which runs before first paint so a reload never
  // flashes the wrong mode. **A nonce is not available here**: the header is one static string set
  // by a Traefik middleware, and a nonce has to be new per response. A hash is the alternative the
  // spec provides, and it works because Vite copies that script into `dist/index.html` byte for
  // byte — `scripts/csp-parity.sh` recomputes it from the source file on every build.
  "script-src 'self' 'sha256-tbzDDqTc7a2d6gVi/Drd9uDCYWdCxXMJEWzje2jcSrQ='",
  // No `'unsafe-inline'`, which is unusual enough to be worth stating: the SPA has no `:style`
  // bindings and no literal `style` attributes, and the build emits one stylesheet and no `<style>`
  // element. Vue sets styles through the CSSOM, which CSP does not govern.
  "style-src 'self'",
  // Both self-hosted, and both are the reason the privacy notice can say no third party is
  // contacted: 17 `@font-face` rules over `/assets/*.woff2`, and one same-origin `fetch` to `/api`.
  "font-src 'self'",
  "connect-src 'self'",
] as const

/**
 * The header value, with the deployment-dependent directive supplied by the caller.
 *
 * A function rather than a constant so neither caller can forget `img-src` and get a policy that
 * silently falls through to `default-src`, which would be right by accident today and wrong the
 * moment `default-src` widens.
 */
export function contentSecurityPolicy(imgSrc: string): string {
  return [...CSP_DIRECTIVES, `img-src ${imgSrc}`].join('; ')
}
