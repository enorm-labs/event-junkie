import { describe, expect, it } from 'vitest'

import {
  CONTACT_DETAILS_ARE_PROVISIONAL,
  CONTROLLER,
  INFRASTRUCTURE_IS_PROPOSED,
  LAST_REVIEWED,
  PROCESSOR_CONTRACTS_PENDING,
} from '@/lib/legal'

/** Matches the deliberately fake German address used until the real one is rented (§8.3). */
const PLACEHOLDER = /Musterstr|Musterstadt/

describe('legal contact details', () => {
  it('keeps the provisional banner in step with the placeholder address', () => {
    // The tripwire. It holds before AND after go-live, so it never needs inverting or skipping:
    // replacing the address without clearing the flag fails here, and clearing the flag while the
    // placeholder is still in place fails here too. Whoever swaps in the Postflex address is
    // forced to touch both, which is exactly the failure this guards against.
    const usesPlaceholder = PLACEHOLDER.test(`${CONTROLLER.street} ${CONTROLLER.city}`)
    expect(CONTACT_DETAILS_ARE_PROVISIONAL).toBe(usesPlaceholder)
  })

  it('has a controller name, which § 5 DDG requires to be a real person, not a project', () => {
    expect(CONTROLLER.name).toBeTruthy()
    expect(CONTROLLER.name).not.toMatch(/event junkie|team/i)
  })

  it('has a postal address rather than a PO box, which is explicitly insufficient', () => {
    expect(CONTROLLER.street).toBeTruthy()
    expect(CONTROLLER.city).toBeTruthy()
    // `careOf` is in the haystack deliberately. A rented address is exactly where a Packstation or
    // a Postfach would plausibly be typed one day, and neither is a ladungsfähige Anschrift.
    expect(`${CONTROLLER.careOf} ${CONTROLLER.street} ${CONTROLLER.city}`).not.toMatch(
      /postfach|p\.?o\.? box|packstation/i,
    )
  })

  // The customer number is what routes the post. An envelope carrying the street but not the
  // number may not arrive, so an address that has lost it is not merely untidy — it is unreachable
  // while looking complete, which is the failure CONTACT_DETAILS_ARE_PROVISIONAL exists to prevent
  // and cannot detect.
  it('keeps the Postflex customer number, without which the address does not forward', () => {
    expect(CONTROLLER.careOf).toMatch(/^c\/o POSTFLEX PFX-\d{3}-\d{3}$/)
  })

  // #275. §5 of the notice names Hetzner as an Art. 28 processor in the present tense, and
  // LEGAL.md §14 is blunt about which way that fails: "A notice naming processors without a DPA in
  // place is worse than one naming none." So the claim and the banner have to move together.
  //
  // Deliberately NOT folded into INFRASTRUCTURE_IS_PROPOSED: that flag says the providers are
  // intended and nothing is deployed, which is a fact about infrastructure. A contract can be
  // concluded before anything is deployed and can lapse long after. Two facts, two flags.
  it('no longer flags the Art. 28 contract as pending, because the AVV is concluded', () => {
    // Concluded 2026-08-19. A plain value assertion, and deliberately so: nothing in code can
    // observe a signed PDF, so unlike the placeholder-address tripwire above there is no second
    // signal to check this against. What it buys is that flipping the flag forces someone to touch
    // this line and say why — which is the only guard available when the fact lives outside the
    // repository. The date is in LEGAL.md §14.
    expect(PROCESSOR_CONTRACTS_PENDING).toBe(false)
  })

  it('records a review date in ISO form so the legal pages can show when they were checked', () => {
    expect(LAST_REVIEWED).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('no longer flags the infrastructure as proposed, because it is deployed and re-checked', () => {
    // #278. Production was deployed on 2026-08-31 (#285) and the notice was re-read against it on
    // 2026-09-02: the processors are real, no IP address is logged, and §2's retention is a volume
    // bound rather than the seven days it used to claim.
    //
    // Flipping this back to `true` is a statement that the notice has stopped being accurate, which
    // is a real thing that can happen — a new processor, an edge provider in front of the origin, a
    // second environment the notice does not describe. **Re-read §2 and §5 before touching it**, and
    // move `LAST_REVIEWED` in the same change; the flag and the date are one claim in two places.
    expect(INFRASTRUCTURE_IS_PROPOSED).toBe(false)
  })
})
