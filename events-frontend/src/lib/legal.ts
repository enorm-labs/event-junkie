/**
 * Shared facts for the legal pages: the imprint and the privacy notice must carry the same
 * controller details (§5 DDG, Art. 13 (1) (a) GDPR), and two copies would disagree
 * (docs/LEGAL.md §8.3).
 */

/**
 * The controller. Real since 2026-08-21: a rented ladungsfähige Anschrift from Postflex (#273)
 * and a role mailbox that receives (#274).
 *
 * `careOf` is its own field because German postal convention puts it on its own line between the
 * name and the street, and the customer number in it is what routes the post; folded into
 * `street` it would read as an address that is almost right.
 */
export const CONTROLLER = {
  name: 'Norman Lange',
  careOf: 'c/o POSTFLEX PFX-665-382',
  street: 'Emsdettener Straße 10',
  city: '48268 Greven',
  email: 'hello@event-junkie.de',
} as const

// The country is deliberately not here: "Germany" and "Deutschland" are one fact worded twice,
// which is what the message catalogue is for (`legal.country`).

/**
 * While `true`, the legal pages say so in a banner rather than presenting placeholder details as
 * fact. `false` since 2026-08-21. Set it back to `true` if the rental lapses or the mailbox stops
 * receiving: an imprint naming an address that no longer forwards fails § 5 DDG while looking
 * finished. `__tests__/legal.spec.ts` holds this and the placeholder in step.
 */
export const CONTACT_DETAILS_ARE_PROVISIONAL = false

/**
 * `false` since the notice was re-read against what runs rather than the plan (#278): §5's
 * processors are the real ones, §2 states that no IP address is logged (`nginx.conf`'s
 * `ej_no_ip`, and Traefik logs nothing), and §2's retention is the volume bound it is.
 *
 * Set it back to `true` if the notice stops describing what runs: a new processor, an edge
 * provider in front of the origin, an environment the notice does not cover. Move
 * {@link LAST_REVIEWED} in the same change; the flag and the date are one claim in two places.
 */
export const INFRASTRUCTURE_IS_PROPOSED = false

/**
 * While `true`, the notice says the Art. 28 processor contract is not yet concluded (#275); a
 * notice naming processors without a DPA is worse than one naming none (LEGAL.md §14).
 * [INFRASTRUCTURE_IS_PROPOSED] cannot stand in for it: a contract can be concluded before
 * anything is deployed and lapse long after.
 *
 * Concluded 2026-08-19 via <https://accounts.hetzner.com/account/dpa>. Nothing in code can
 * observe a signed PDF, so this constant is the record. Set it back to `true` if the contract
 * lapses, is superseded, or a second processor is added without one.
 */
export const PROCESSOR_CONTRACTS_PENDING = false

/**
 * Date the legal pages were last reviewed against what the system does (§7.7). The review reads
 * the deployed configuration, not the plan: #279's pass found the notice still describing image
 * requests to venue servers that `images.serving.enabled` had stopped three days earlier.
 */
export const LAST_REVIEWED = '2026-09-09'

/**
 * The supervisory authority (Art. 13 (2) (d) GDPR). Competence follows where the controller is
 * established, not the rented address in {@link CONTROLLER}, so the two name different
 * Bundesländer on purpose. #279 asks a reviewer to confirm it.
 */
export const SUPERVISORY_AUTHORITY = {
  name: 'Berliner Beauftragte für Datenschutz und Informationsfreiheit',
  url: 'https://www.datenschutz-berlin.de/',
} as const
