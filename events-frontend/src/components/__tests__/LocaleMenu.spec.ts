import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import LocaleMenu from '@/components/LocaleMenu.vue'

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

async function openAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
  })
  await router.push(path)
  wrapper = mount(LocaleMenu, { global: { plugins: [router] }, attachTo: document.body })
  await wrapper.get('button').trigger('click')
  await flushPromises()
}

function link(name: string): HTMLAnchorElement {
  const found = [...document.body.querySelectorAll('a')].find((a) => a.textContent?.trim() === name)
  if (!found) throw new Error(`no link named ${name}`)
  return found
}

describe('LocaleMenu', () => {
  afterEach(() => {
    wrapper?.unmount()
  })

  it('is an icon button named for what it does, with no links until opened', () => {
    wrapper = mount(LocaleMenu)

    expect(wrapper.get('button').attributes('aria-label')).toBe('Language')
    expect(document.body.querySelectorAll('a[hreflang]')).toHaveLength(0)
  })

  it('links each locale to the current page, query and hash kept', async () => {
    await openAt('/en/events?q=Tresor&district=mitte&district=kreuzberg#list')

    const german = link('Deutsch')
    expect(german.getAttribute('href')).toBe(
      '/de/events?q=Tresor&district=mitte&district=kreuzberg#list',
    )
    expect(german.getAttribute('hreflang')).toBe('de')
    expect(german.getAttribute('lang')).toBe('de')
  })

  it('marks the current locale and only that one', async () => {
    await openAt('/de/about')

    expect(link('Deutsch').getAttribute('aria-current')).toBe('true')
    expect(link('English').hasAttribute('aria-current')).toBe(false)
  })
})
