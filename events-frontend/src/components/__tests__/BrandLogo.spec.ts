import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import BrandLogo from '@/components/BrandLogo.vue'

/**
 * Two behaviours, neither of which is visible in a screenshot and both of which a logo change can
 * silently break.
 *
 * The badge/wordmark swap is asserted through the utility classes rather than by measuring layout:
 * jsdom applies no CSS, so `sm:hidden` never takes effect here. The classes *are* the contract —
 * whether Tailwind honours them is Tailwind's problem, and the e2e overflow guard in
 * `e2e/smoke.spec.ts` is what proves the result at a real viewport.
 */
describe('BrandLogo', () => {
  it('keeps the accessible name at every width, badge or wordmark', () => {
    // Below `sm` the wordmark is `sr-only` rather than absent, so a link wrapping this lockup is
    // never named by the decorative badge alone.
    for (const alwaysShowWordmark of [false, true]) {
      const wrapper = mount(BrandLogo, { props: { alwaysShowWordmark } })
      expect(wrapper.text().replace(/\s+/g, ' ')).toContain('Event Junkie')
    }
  })

  it('shows the badge only below sm, and hides it once the wordmark appears', () => {
    // Never both: they are the same two letters, so together they are tautological.
    const wrapper = mount(BrandLogo)
    const badge = wrapper.find('svg')
    expect(badge.exists()).toBe(true)
    expect(badge.classes()).toContain('sm:hidden')
    expect(wrapper.get('span > span').classes()).toEqual(
      expect.arrayContaining(['sr-only', 'sm:not-sr-only']),
    )
  })

  it('drops the badge entirely when the wordmark is always shown', () => {
    // The footer stacks its columns and has the room; a badge on its own there reads as a stray
    // icon, and beside the wordmark it is redundant.
    const wrapper = mount(BrandLogo, { props: { alwaysShowWordmark: true } })
    expect(wrapper.find('svg').exists()).toBe(false)
    expect(wrapper.get('span > span').classes()).not.toContain('sr-only')
  })

  it('marks the badge decorative, so it never doubles the accessible name', () => {
    const wrapper = mount(BrandLogo)
    expect(wrapper.get('svg').attributes('aria-hidden')).toBe('true')
  })
})
