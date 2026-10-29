import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

import { ApiError } from '@/api/client'

type Passkey = { id: string; name: string; createdAt: string; lastUsedAt?: string }

const account = vi.hoisted(() => ({
  fetchPasskeys: vi.fn<() => Promise<Passkey[]>>(),
  addPasskey: vi.fn<(name: string) => Promise<void>>(),
  renamePasskey: vi.fn<(id: string, name: string) => Promise<void>>(),
  deletePasskey: vi.fn<(id: string) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: PasskeySettings } = await import('@/components/PasskeySettings.vue')

const phone: Passkey = { id: 'p1', name: 'iPhone', createdAt: '2026-10-20T10:00:00Z' }

let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  vi.stubGlobal('PublicKeyCredential', function PublicKeyCredential() {})
  account.fetchPasskeys.mockResolvedValue([phone])
  account.addPasskey.mockResolvedValue()
  account.renamePasskey.mockResolvedValue()
  account.deletePasskey.mockResolvedValue()
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

async function mountSettings() {
  wrapper = mount(PasskeySettings)
  await flushPromises()
  return wrapper
}

function button(view: VueWrapper, label: string) {
  const found = view.findAll('button').find((b) => b.text() === label)
  if (!found) throw new Error(`No button "${label}"`)
  return found
}

describe('PasskeySettings', () => {
  it('is not there in a browser without passkeys', async () => {
    vi.unstubAllGlobals()
    const view = await mountSettings()
    expect(view.text()).toBe('')
    expect(account.fetchPasskeys).not.toHaveBeenCalled()
  })

  it('lists the passkeys with the date each was added', async () => {
    const view = await mountSettings()
    expect(view.text()).toContain('iPhone')
    expect(view.text()).toContain('Added')
    expect(view.text()).toContain('2026')
  })

  it('adds one named for the device, then lists it', async () => {
    vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
      'Mozilla/5.0 (Macintosh; Intel Mac OS X 15_4) AppleWebKit/605.1.15',
    )
    const view = await mountSettings()
    account.fetchPasskeys.mockResolvedValue([phone, { ...phone, id: 'p2', name: 'Mac' }])
    await button(view, 'Add a passkey').trigger('click')
    await flushPromises()
    expect(account.addPasskey).toHaveBeenCalledWith('Mac')
    expect(view.text()).toContain('Mac')
  })

  it('says nothing when the visitor closes the browser dialog', async () => {
    account.addPasskey.mockRejectedValue(new DOMException('closed', 'NotAllowedError'))
    const view = await mountSettings()
    await button(view, 'Add a passkey').trigger('click')
    await flushPromises()
    expect(view.find('[role="alert"]').exists()).toBe(false)
  })

  it.each([
    ['InvalidStateError', 'already holds a passkey'],
    ['SecurityError', 'cannot make a passkey'],
    ['NotSupportedError', 'cannot make a passkey'],
  ])('names a %s from the browser', async (name, text) => {
    account.addPasskey.mockRejectedValue(new DOMException('refused', name))
    const view = await mountSettings()
    await button(view, 'Add a passkey').trigger('click')
    await flushPromises()
    expect(view.find('[role="alert"]').text()).toContain(text)
  })

  it('adds the name of an error it does not know, never its message', async () => {
    account.addPasskey.mockRejectedValue(new TypeError('Mac mini of someone'))
    const view = await mountSettings()
    await button(view, 'Add a passkey').trigger('click')
    await flushPromises()
    const alert = view.find('[role="alert"]').text()
    expect(alert).toContain('That did not work. Try again. (TypeError)')
    expect(alert).not.toContain('Mac mini')
  })

  it('names the limit at ten', async () => {
    account.addPasskey.mockRejectedValue(new ApiError(409, 'Conflict'))
    const view = await mountSettings()
    await button(view, 'Add a passkey').trigger('click')
    await flushPromises()
    expect(view.find('[role="alert"]').text()).toContain('ten passkeys')
  })

  it('renames and deletes one', async () => {
    const view = await mountSettings()
    await button(view, 'Rename').trigger('click')
    await view.find('input').setValue('Work phone')
    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.renamePasskey).toHaveBeenCalledWith('p1', 'Work phone')
    expect(view.text()).toContain('Work phone')

    await button(view, 'Delete').trigger('click')
    await flushPromises()
    expect(account.deletePasskey).toHaveBeenCalledWith('p1')
    expect(view.find('li').exists()).toBe(false)
  })
})
