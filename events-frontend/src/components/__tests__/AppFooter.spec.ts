import { describe, expect, it } from 'vitest'

import { mount, type VueWrapper } from '@vue/test-utils'
import AppFooter from '@/components/AppFooter.vue'
import { REPOSITORY_URL } from '@/lib/links'

/** Stub RouterLink to a plain anchor so in-app routes are assertable without a full router. */
const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

const mount_ = () => mount(AppFooter, { global: { stubs } })

/** Links that leave the site — everything except the in-app `/legal/*` routes. */
const external = (wrapper: VueWrapper) =>
  wrapper.findAll('a').filter((a) => !a.attributes('href')?.startsWith('/'))

describe('AppFooter', () => {
  it('renders the disclaimer, which is the point of the footer body', () => {
    const wrapper = mount_()
    expect(wrapper.text()).toContain('provided without warranty')
    expect(wrapper.text()).toContain('check with the venue')
  })

  it('separates the copyright from the code licence', () => {
    // The two clauses must stay distinct: a bare "© Event Junkie · Apache-2.0" would imply the
    // event data is ours to license, which it is not (docs/LEGAL.md §3).
    const wrapper = mount_()
    expect(wrapper.text()).toContain('© 2026 Event Junkie')
    expect(wrapper.text()).toContain('Code under Apache-2.0')
  })

  it('links the licence line to the LICENSE file rather than the repository root', () => {
    const wrapper = mount_()
    const licence = wrapper.get('a[href$="/blob/main/LICENSE"]')
    expect(licence.text()).toContain('Apache-2.0')
  })

  it('points every external link at the project repository', () => {
    const wrapper = mount_()
    const hrefs = external(wrapper).map((a) => a.attributes('href') ?? '')
    expect(hrefs.length).toBeGreaterThan(0)
    expect(hrefs.every((href) => href.startsWith(REPOSITORY_URL))).toBe(true)
  })

  it('opens external links safely', () => {
    const wrapper = mount_()
    for (const link of external(wrapper)) {
      expect(link.attributes('target')).toBe('_blank')
      expect(link.attributes('rel')).toContain('noopener')
    }
  })

  it('links the legal pages as in-app routes, not as external links', () => {
    const wrapper = mount_()
    const legal = wrapper.findAll('a').filter((a) => a.attributes('href')?.includes('/legal/'))
    // Locale-prefixed: every in-app link carries the active locale (ADR-013 §Decision 2).
    expect(legal.map((a) => a.attributes('href'))).toEqual([
      '/en/legal/imprint',
      '/en/legal/privacy',
      '/en/legal/notices',
      '/en/legal/for-venues',
    ])
    // A RouterLink, so no full page reload and no new tab.
    for (const link of legal) expect(link.attributes('target')).toBeUndefined()
  })

  it('links the contribution guide', () => {
    const wrapper = mount_()
    const contributing = wrapper.get('a[href$="/blob/main/CONTRIBUTING.md"]')
    expect(contributing.text()).toBe('Contributing')
  })

  it('gives every landmark in the footer an accessible name', () => {
    // The footer contributes three navigation landmarks alongside the header's. With more than
    // one, each needs a distinguishable name or the landmark list is a row of "navigation".
    const wrapper = mount_()
    const names = wrapper.findAll('nav').map((nav) => {
      const headingId = nav.attributes('aria-labelledby')
      // The link groups are labelled by their visible heading; the locale switcher has no heading
      // of its own and carries an aria-label instead.
      const name = headingId ? wrapper.get(`#${headingId}`).text() : nav.attributes('aria-label')
      expect(name, 'a footer landmark has no accessible name').toBeTruthy()
      return name
    })
    expect(names).toEqual(['Project', 'Legal', 'Language'])
  })
})
