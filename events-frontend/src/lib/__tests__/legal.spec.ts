import { describe, expect, it } from 'vitest'

import {
  CONTACT_DETAILS_ARE_PROVISIONAL,
  CONTROLLER,
  INFRASTRUCTURE_IS_PROPOSED,
  LAST_REVIEWED,
  PROCESSOR_CONTRACTS_PENDING,
} from '@/lib/legal'

/** Matches the deliberately fake German address the placeholder used (§8.3). */
const PLACEHOLDER = /Musterstr|Musterstadt/

describe('legal contact details', () => {
  it('keeps the provisional banner in step with the placeholder address', () => {
    // The tripwire, holding before AND after go-live: replacing the address without clearing the
    // flag fails here, and clearing the flag with the placeholder in place fails too.
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
    // `careOf` is in the haystack: a rented address is where a Packstation or a Postfach would be
    // typed one day, and neither is a ladungsfähige Anschrift.
    expect(`${CONTROLLER.careOf} ${CONTROLLER.street} ${CONTROLLER.city}`).not.toMatch(
      /postfach|p\.?o\.? box|packstation/i,
    )
  })

  // The customer number is what routes the post; an address that has lost it is unreachable while
  // looking complete, which CONTACT_DETAILS_ARE_PROVISIONAL cannot detect.
  it('keeps the Postflex customer number, without which the address does not forward', () => {
    expect(CONTROLLER.careOf).toMatch(/^c\/o POSTFLEX PFX-\d{3}-\d{3}$/)
  })

  // #275: §5 names Hetzner as an Art. 28 processor in the present tense, and a notice naming
  // processors without a DPA is worse than one naming none (LEGAL.md §14). Not folded into
  // INFRASTRUCTURE_IS_PROPOSED: a contract can be concluded before anything is deployed and lapse
  // long after. Two facts, two flags.
  it('no longer flags the Art. 28 contract as pending, because the AVV is concluded', () => {
    // Concluded 2026-08-19 (LEGAL.md §14). A plain value assertion: nothing in code can observe a
    // signed PDF, so flipping the flag forces someone to touch this line and say why.
    expect(PROCESSOR_CONTRACTS_PENDING).toBe(false)
  })

  it('records a review date in ISO form so the legal pages can show when they were checked', () => {
    expect(LAST_REVIEWED).toMatch(/^\d{4}-\d{2}-\d{2}$/)
  })

  it('no longer flags the infrastructure as proposed, because it is deployed and re-checked', () => {
    // #278: production was deployed on 2026-08-31 (#285) and the notice re-read against it on
    // 2026-09-02. Flipping this back to `true` says the notice has stopped being accurate: a new
    // processor, an edge provider, an environment it does not describe. Re-read §2 and §5 first, and
    // move `LAST_REVIEWED` in the same change.
    expect(INFRASTRUCTURE_IS_PROPOSED).toBe(false)
  })
})
