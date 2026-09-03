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
 * Real since 2026-08-21: a rented *ladungsfähige Anschrift* from Postflex (#273) and a real role
 * mailbox (#274). Both were placeholders until then, guarded by
 * {@link CONTACT_DETAILS_ARE_PROVISIONAL} — see docs/LEGAL.md §8.3.
 *
 * `careOf` is its own field rather than part of `street`, because German postal convention puts
 * it on its own line **between** the name and the street, and because the customer number in it
 * is what routes the post: an envelope without it may not arrive. Folding the two together would
 * render one line and read as an address that is almost right, which is the worst kind.
 */
export const CONTROLLER = {
  name: 'Norman Lange',
  careOf: 'c/o POSTFLEX PFX-665-382',
  street: 'Emsdettener Straße 10',
  city: '48268 Greven',
  email: 'hello@event-junkie.de',
} as const

// The country is deliberately *not* here. Street and city are proper nouns and read the same in
// every language; a country name does not — "Germany" and "Deutschland" are the same fact worded
// twice, which is what the message catalogue is for. It lives at `legal.country`.

/**
 * While `true`, the legal pages say so in a banner rather than presenting placeholder details as
 * fact — a legal page that quietly states a false address is worse than one that admits it is not
 * final.
 *
 * `false` since 2026-08-21: the Postflex address is rented and in {@link CONTROLLER}, and the
 * mailbox it names receives. **Set it back to `true` if either stops being true** — a lapsed
 * rental leaves an imprint naming an address that no longer forwards, which fails § 5 DDG while
 * looking entirely finished. The guard test in `__tests__/legal.spec.ts` holds this and the
 * placeholder in step in both directions.
 */
export const CONTACT_DETAILS_ARE_PROVISIONAL = false

/**
 * The deployment the privacy notice describes — Hetzner in Germany, nothing in front of it, per
 * ADR-012 as amended to drop Cloudflare — is built and running (#285).
 *
 * `false` since the notice was re-read against what actually runs rather than against the plan
 * (#278): §5's processors are the real ones, §2 no longer hedges on whether an IP address is logged
 * (it is not — `nginx.conf`'s `ej_no_ip` omits it and Traefik logs nothing), and §2's retention is
 * the volume bound it is instead of a period. **Provisioning alone was not the moment to flip
 * this**; re-reading the prose was, which is why it did not move with the deployment.
 *
 * **Set it back to `true` if the notice stops describing what runs** — a new processor, an edge
 * provider in front of the origin, an environment the notice does not cover. Move
 * {@link LAST_REVIEWED} in the same change: the flag and the date are one claim in two places.
 */
export const INFRASTRUCTURE_IS_PROPOSED = false

/**
 * While `true`, the notice says the Art. 28 processor contract is **not yet concluded** (#275).
 *
 * §5 names Hetzner as an `Auftragsverarbeiter mit einem Vertrag nach Art. 28 DSGVO`, in the present
 * tense — a statement of fact, and [LEGAL.md](../../../docs/LEGAL.md) §14 is blunt about which way
 * it fails: *a notice naming processors without a DPA in place is worse than one naming none*.
 * [INFRASTRUCTURE_IS_PROPOSED] cannot stand in for it: a contract can be concluded before anything
 * is deployed and lapse long after, so they are two facts and two flags.
 *
 * Concluded 2026-08-19 via <https://accounts.hetzner.com/account/dpa>, so this is `false`. Nothing
 * in code can observe a signed PDF, which makes this constant the record, carrying the same date as
 * LEGAL.md §14. **Set it back to `true` if the contract lapses, is superseded, or a second
 * processor is added without one** — the failure is silent in both directions.
 */
export const PROCESSOR_CONTRACTS_PENDING = false

/**
 * Date the legal pages were last reviewed against what the system actually does (§7.7).
 *
 * The review reads the deployed configuration, not the plan and not the previous review. #279's
 * pass found the notice still describing image requests to venue servers, which
 * `images.serving.enabled` had stopped three days earlier, and §3 claiming one stored key where the
 * site writes two.
 */
export const LAST_REVIEWED = '2026-09-03'

/**
 * The supervisory authority named under Art. 13 (2) (d) GDPR.
 *
 * Competence follows where the controller is **established**, which is not the rented
 * *ladungsfähige Anschrift* in {@link CONTROLLER}. The two therefore name different Bundesländer on
 * purpose, and a reader sees both. #279 asks a reviewer to confirm it and to say whether the page
 * has to reconcile them.
 */
export const SUPERVISORY_AUTHORITY = {
  name: 'Berliner Beauftragte für Datenschutz und Informationsfreiheit',
  url: 'https://www.datenschutz-berlin.de/',
} as const
