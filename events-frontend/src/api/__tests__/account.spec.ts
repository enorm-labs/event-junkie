import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn<(path: string) => Promise<unknown>>())
const signal = vi.hoisted(() => vi.fn<(rpId?: string, id?: string) => Promise<void>>())
const signalAccepted = vi.hoisted(() => vi.fn<(accepted: unknown) => Promise<void>>())
const del = vi.hoisted(() => vi.fn<(path: string) => Promise<unknown>>())
const get = vi.hoisted(() => vi.fn<(path: string) => Promise<unknown>>())

vi.mock('../client', async (original) => ({
  ...(await original<typeof import('../client')>()),
  api: { POST: post, DELETE: del, GET: get },
}))
vi.mock('@/lib/webauthn', async (original) => ({
  ...(await original<typeof import('@/lib/webauthn')>()),
  credentialJson: () => ({ id: 'cred-1' }),
  signalUnknownPasskey: signal,
  signalAcceptedPasskeys: signalAccepted,
}))

import { deleteAccount, signInWithPasskey } from '@/api/account'
import { ApiError } from '@/api/client'

const answer = (status: number) => ({
  data: status === 200 ? { email: 'ada@example.com' } : undefined,
  error: status === 200 ? undefined : { detail: 'no' },
  response: new Response(null, { status }),
})

beforeEach(() => {
  vi.stubGlobal('navigator', {
    credentials: { get: vi.fn<() => Promise<unknown>>().mockResolvedValue({ id: 'cred-1' }) },
  })
  post.mockImplementation(async (path: string) =>
    path === '/api/auth/passkey/options'
      ? { data: { challenge: 'AQID', rpId: 'event-junkie.de' }, response: new Response() }
      : signInAnswer,
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
  signal.mockReset()
  signalAccepted.mockReset()
})

let signInAnswer = answer(200)

describe('signInWithPasskey', () => {
  it('signals an unknown passkey with the server rpId, and still fails', async () => {
    signInAnswer = answer(404)
    await expect(signInWithPasskey()).rejects.toMatchObject({ status: 404 })
    expect(signal).toHaveBeenCalledWith('event-junkie.de', 'cred-1')
  })

  it('does not signal a passkey that only failed to verify', async () => {
    signInAnswer = answer(401)
    await expect(signInWithPasskey()).rejects.toBeInstanceOf(ApiError)
    expect(signal).not.toHaveBeenCalled()
  })

  it('does not signal on a sign-in', async () => {
    signInAnswer = answer(200)
    await expect(signInWithPasskey()).resolves.toEqual({ email: 'ada@example.com' })
    expect(signal).not.toHaveBeenCalled()
  })
})

describe('deleteAccount', () => {
  const accepted = { rpId: 'event-junkie.de', userId: 'aGFuZGxl', allAcceptedCredentialIds: ['c1'] }

  it('tells the password manager that no passkey is left, once the account is gone', async () => {
    get.mockResolvedValue({ data: accepted, response: new Response() })
    del.mockResolvedValue({ response: new Response(null, { status: 204 }) })
    await deleteAccount()
    expect(signalAccepted).toHaveBeenCalledWith({ ...accepted, allAcceptedCredentialIds: [] })
  })

  it('signals nothing when the delete fails, or the account never made a passkey', async () => {
    get.mockResolvedValue({ data: accepted, response: new Response() })
    del.mockResolvedValue({ error: {}, response: new Response(null, { status: 500 }) })
    await expect(deleteAccount()).rejects.toBeInstanceOf(ApiError)

    get.mockResolvedValue({ response: new Response(null, { status: 204 }) })
    del.mockResolvedValue({ response: new Response(null, { status: 204 }) })
    await deleteAccount()
    expect(signalAccepted).not.toHaveBeenCalled()
  })
})
