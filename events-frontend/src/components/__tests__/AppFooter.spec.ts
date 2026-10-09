import { afterEach, describe, expect, it } from 'vitest'

import { enableAutoUnmount, mount, type VueWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { setI18nLocale } from '@/i18n'
import AppFooter from '@/components/AppFooter.vue'
import { CONTROLLER } from '@/lib/legal'
import { REPOSITORY_URL } from '@/lib/links'

/** Stub RouterLink to a plain anchor so in-app routes are assertable without a full router. */
const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

// The week tests switch the locale, which re-renders every footer still mounted.
enableAutoUnmount(afterEach)

const mount_ = () => mount(AppFooter, { global: { stubs } })

/** Links that open another site: neither the in-app `/legal/*` routes nor the mail link. */
const external = (wrapper: VueWrapper) =>
  wrapper.findAll('a').filter((a) => /^https?:/.test(a.attributes('href') ?? ''))

describe('AppFooter', () => {
  it('renders the disclaimer, which is the point of the footer body', () => {
    const wrapper = mount_()
    expect(wrapper.text()).toContain('provided without warranty')
    expect(wrapper.text()).toContain('check with the venue')
  })

  it('separates the copyright from the code licence', () => {
    // The two clauses must stay distinct: a bare "© Event Junkie · BUSL-1.1" would imply the
    // event data is ours to license, which it is not (docs/LEGAL.md §3).
    const wrapper = mount_()
    expect(wrapper.text()).toContain('© 2026 Event Junkie')
    expect(wrapper.text()).toContain('Code under BUSL-1.1')
  })

  it('links the licence line to the LICENSE file rather than the repository root', () => {
    const wrapper = mount_()
    const licence = wrapper.get('a[href$="/blob/main/LICENSE"]')
    expect(licence.text()).toContain('BUSL-1.1')
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

  it('offers feedback by mail, which needs no GitHub account', () => {
    const wrapper = mount_()
    const mail = wrapper.get(`a[href^="mailto:${CONTROLLER.email}"]`)
    expect(mail.text()).toBe('Feedback by email')
    expect(mail.attributes('target')).toBeUndefined()
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

describe('AppFooter week link', () => {
  // A real router, so the link takes its locale from the URL as it does on the site.
  async function mountAt(locale: 'en' | 'de') {
    const Page = defineComponent({ render: () => h('p') })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
    })
    await router.push(`/${locale}`)
    setI18nLocale(locale)
    return mount(AppFooter, { global: { plugins: [router] } })
  }

  afterEach(() => setI18nLocale('en'))

  it.each([
    ['en', 'This week in Berlin'],
    ['de', 'Diese Woche in Berlin'],
  ] as const)('links this week in %s beside the feed', async (locale, label) => {
    const wrapper = await mountAt(locale)
    const week = wrapper.get(`a[href="/${locale}/week"]`)
    expect(week.text()).toBe(label)
    expect(week.get('svg').attributes('aria-hidden')).toBe('true')
    // Same row as the feed link, so the two read as the visitor-facing pair.
    expect(week.element.parentElement?.querySelector('a[type]')).not.toBeNull()
  })
})
