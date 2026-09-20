/**
 * The Content-Security-Policy, minus the one directive that depends on the deployment. The
 * second of two copies, and `scripts/csp-parity.sh` stops them drifting: production gets the
 * header from Traefik (`deploy/charts/event-junkie/values.yaml`), and this one makes
 * `npm run preview`, the server Playwright runs against on CI, serve the built site under the
 * same rules. Without it the first evidence of a wrong policy is a blank page on staging (#846).
 *
 * `img-src` is absent because neither side can state it alone: the chart derives it from
 * `images.serving.enabled`, since while serving is off `'self'` would blank every image.
 */
export const CSP_DIRECTIVES = [
  "default-src 'self'",
  // A relative URL must keep meaning what it says, and nothing here is meant to be embedded;
  // `frame-ancestors` repeats the middleware's `frameDeny` for browsers that stopped reading
  // X-Frame-Options.
  "base-uri 'self'",
  "object-src 'none'",
  "frame-src 'none'",
  "frame-ancestors 'none'",
  "form-action 'self'",
  // The hash is of the theme script in index.html, which runs before first paint. A nonce is not
  // available: the header is one static string set by a Traefik middleware. It works because Vite
  // copies that script into `dist/index.html` byte for byte; `scripts/csp-parity.sh` recomputes
  // it on every build.
  "script-src 'self' 'sha256-DuAtP0bDA+RpucmlfZGOJZext9f2E8NeIekOECvRyQ4='",
  // No `'unsafe-inline'`: the SPA has no `:style` bindings and no literal `style` attributes, the
  // build emits one stylesheet and no `<style>` element, and Vue sets styles through the CSSOM,
  // which CSP does not govern.
  "style-src 'self'",
  // Both self-hosted, which is why the privacy notice can say no third party is contacted: 17
  // `@font-face` rules over `/assets/*.woff2`, one same-origin `fetch` to `/api`.
  "font-src 'self'",
  "connect-src 'self'",
] as const

/**
 * A function rather than a constant so neither caller can forget `img-src` and fall through to
 * `default-src`, right by accident today and wrong the moment it widens.
 */
export function contentSecurityPolicy(imgSrc: string): string {
  return [...CSP_DIRECTIVES, `img-src ${imgSrc}`].join('; ')
}
