/**
 * Shared facts for the legal pages.
 *
 * One module because the imprint and the privacy notice must carry **the same** controller
 * details — §5 DDG and Art. 13 (1) (a) GDPR each require them, and two hand-maintained copies
 * would eventually disagree. See docs/LEGAL.md §8.3.
 */

/**
 * The controller / service provider.
 *
 * TODO(imprint-address): the postal address and email are placeholders. Replace both with the
 * rented Postflex address and the real role mailbox once `event-junkie.de` is registered, and
 * set {@link CONTACT_DETAILS_ARE_PROVISIONAL} to `false` in the same commit — a unit test holds
 * the two in step. See docs/LEGAL.md §8.3.
 */
export const CONTROLLER = {
  name: 'Norman Lange',
  street: 'Musterstraße 1',
  city: '12345 Musterstadt',
  email: 'hello@event-junkie.de',
} as const

// The country is deliberately *not* here. Street and city are proper nouns and read the same in
// every language; a country name does not — "Germany" and "Deutschland" are the same fact worded
// twice, which is what the message catalogue is for. It lives at `legal.country`.

/**
 * While `true`, the legal pages say so in a banner rather than presenting placeholder details as
 * fact — a legal page that quietly states a false address is worse than one that admits it is not
 * final. Must be `false` before go-live; the guard test in `__tests__/legal.spec.ts` fails if this
 * and the placeholder address ever disagree.
 */
export const CONTACT_DETAILS_ARE_PROVISIONAL = true

/**
 * The deployment the privacy notice describes (Cloudflare in front of Hetzner) is **decided but
 * not built** — ADR-012 was accepted on 2026-08-10, which settled the platform and changed nothing
 * about what is running. Nothing is deployed. Until it exists, the notice describes an intent, and
 * says so. Set to `false` when the platform is actually provisioned and the notice has been
 * re-checked against what runs — accepting the ADR is not that moment.
 */
export const INFRASTRUCTURE_IS_PROPOSED = true

/** Date the legal pages were last reviewed against what the system actually does (§7.7). */
export const LAST_REVIEWED = '2026-08-08'

/** The supervisory authority for a controller established in Berlin (Art. 13 (2) (d) GDPR). */
export const SUPERVISORY_AUTHORITY = {
  name: 'Berliner Beauftragte für Datenschutz und Informationsfreiheit',
  url: 'https://www.datenschutz-berlin.de/',
} as const
