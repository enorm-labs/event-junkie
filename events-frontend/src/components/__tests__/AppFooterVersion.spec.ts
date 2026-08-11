import { beforeEach, describe, expect, it, vi } from 'vitest'

import { flushPromises, mount } from '@vue/test-utils'
import AppFooter from '@/components/AppFooter.vue'
import { resetAppMetaForTests } from '@/composables/useAppMeta'
import type { AppMeta } from '@/api/types'

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
}

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<() => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

/** Mounts the footer with `/meta` resolving to `meta`, and waits for the fetch to settle. */
async function mountWithMeta(meta: AppMeta | null) {
  getMock.mockReturnValue(
    meta === null ? Promise.reject(new Error('offline')) : Promise.resolve(meta),
  )
  const wrapper = mount(AppFooter, { global: { stubs } })
  await flushPromises()
  return wrapper
}

const version = (wrapper: { find: (s: string) => { exists: () => boolean; text: () => string } }) =>
  wrapper.find('[data-testid="app-version"]')

describe('AppFooter version line', () => {
  beforeEach(() => {
    resetAppMetaForTests()
    getMock.mockReset()
  })

  it('shows the version and short commit reported by the backend', async () => {
    const wrapper = await mountWithMeta({
      version: '0.1.0',
      commit: '9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3',
      commitShort: '9f1a2b3',
      buildTime: '2026-08-07T12:49:19Z',
    })

    expect(version(wrapper).text()).toContain('v0.1.0')
    expect(version(wrapper).text()).toContain('9f1a2b3')
  })

  it('links a released version to its tag and the commit to its full sha', async () => {
    const wrapper = await mountWithMeta({
      version: '0.1.0',
      commit: '9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3',
      commitShort: '9f1a2b3',
    })

    const hrefs = wrapper.findAll('a').map((a) => a.attributes('href'))
    expect(hrefs).toContain('https://github.com/enorm-labs/event-junkie/releases/tag/v0.1.0')
    // The FULL sha, not the displayed short form — otherwise the link resolves by luck at best.
    expect(hrefs).toContain(
      'https://github.com/enorm-labs/event-junkie/commit/9f1a2b3c4d5e6f708192a3b4c5d6e7f809a1b2c3',
    )
  })

  it('does not link a -SNAPSHOT version, because no such release tag exists', async () => {
    const wrapper = await mountWithMeta({
      version: '0.1.0-SNAPSHOT',
      commit: null,
      commitShort: null,
    })

    expect(version(wrapper).text()).toContain('v0.1.0-SNAPSHOT')
    const hrefs = wrapper.findAll('a').map((a) => a.attributes('href') ?? '')
    expect(hrefs.some((href) => href.includes('/releases/tag/'))).toBe(false)
  })

  it('does not link a dev build either', async () => {
    const wrapper = await mountWithMeta({ version: 'dev', commit: null, commitShort: null })

    expect(version(wrapper).text()).toContain('vdev')
    const hrefs = wrapper.findAll('a').map((a) => a.attributes('href') ?? '')
    expect(hrefs.some((href) => href.includes('/releases/tag/'))).toBe(false)
  })

  it('omits the commit when the build stamped none, rather than linking nowhere', async () => {
    const wrapper = await mountWithMeta({ version: '0.1.0', commit: null, commitShort: null })

    const hrefs = wrapper.findAll('a').map((a) => a.attributes('href') ?? '')
    expect(hrefs.some((href) => href.includes('/commit/'))).toBe(false)
  })

  it('renders no version line at all when the backend is unreachable', async () => {
    // A footer must not show an error, and must not reserve empty space, for something this minor.
    const wrapper = await mountWithMeta(null)

    expect(version(wrapper).exists()).toBe(false)
    expect(wrapper.text()).toContain('© 2026 Event Junkie')
  })

  it('fetches /meta once even when several components ask for it', async () => {
    await mountWithMeta({ version: '0.1.0', commit: null, commitShort: null })
    mount(AppFooter, { global: { stubs } })
    await flushPromises()

    expect(getMock).toHaveBeenCalledTimes(1)
  })
})
