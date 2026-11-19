import { afterEach, describe, expect, it, vi } from 'vitest'

import { flushPromises, mount } from '@vue/test-utils'
import ShareMenu from '@/components/ShareMenu.vue'

const URL_ = 'https://event-junkie.de/en/artists/sesh-orka'

// The menu's portal and open state are reka-ui's; here the items render in place and a click selects.
const stubs = {
  DropdownMenu: { template: '<div><slot /></div>' },
  DropdownMenuTrigger: { template: '<div><slot /></div>' },
  DropdownMenuContent: { template: '<div role="menu"><slot /></div>' },
  DropdownMenuItem: {
    template: '<button role="menuitem" @click="$emit(\'select\')"><slot /></button>',
    emits: ['select'],
  },
}

function mountMenu(slots: Record<string, string> = {}) {
  return mount(ShareMenu, {
    props: { title: 'Sesh Orka', url: URL_ },
    slots,
    global: { stubs },
  })
}

function item(wrapper: ReturnType<typeof mountMenu>, label: string) {
  const found = wrapper.findAll('[role="menuitem"]').find((each) => each.text() === label)
  if (!found) throw new Error(`no menu item "${label}"`)
  return found
}

function lastStatus(wrapper: ReturnType<typeof mountMenu>): unknown[] | undefined {
  const updates = wrapper.emitted('update:status') ?? []
  return updates[updates.length - 1]
}

describe('ShareMenu', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('offers the share sheet only where the browser has one', () => {
    vi.stubGlobal('navigator', { share: vi.fn<() => Promise<void>>() })
    expect(mountMenu().text()).toContain('Share via…')

    vi.stubGlobal('navigator', {})
    const bare = mountMenu()
    expect(bare.text()).not.toContain('Share via…')
    expect(bare.text()).toContain('Copy link')
  })

  it('copies the link and says so', async () => {
    const writeText = vi.fn<(text: string) => Promise<void>>().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { clipboard: { writeText } })
    const wrapper = mountMenu()

    await item(wrapper, 'Copy link').trigger('click')
    await flushPromises()

    expect(writeText).toHaveBeenCalledWith(URL_)
    expect(lastStatus(wrapper)).toEqual(['Link copied.'])
  })

  it('shows the link when it cannot copy it', async () => {
    vi.stubGlobal('navigator', {})
    const wrapper = mountMenu()

    await item(wrapper, 'Copy link').trigger('click')
    await flushPromises()

    expect(lastStatus(wrapper)).toEqual([`Could not copy the link. Here it is: ${URL_}`])
  })

  it('passes the title and the link to the share sheet', async () => {
    const share = vi.fn<(data: ShareData) => Promise<void>>().mockResolvedValue(undefined)
    vi.stubGlobal('navigator', { share })
    const wrapper = mountMenu()

    await item(wrapper, 'Share via…').trigger('click')
    await flushPromises()

    expect(share).toHaveBeenCalledWith({ title: 'Sesh Orka', url: URL_ })
    // The sheet confirms on its own, so the page announces nothing.
    expect(wrapper.emitted('update:status')).toBeUndefined()
  })

  it('adds the slot after its own items', () => {
    vi.stubGlobal('navigator', {})
    const wrapper = mountMenu({ default: '<button role="menuitem">Extra</button>' })

    expect(wrapper.findAll('[role="menuitem"]').map((each) => each.text())).toEqual([
      'Copy link',
      'Extra',
    ])
  })
})
