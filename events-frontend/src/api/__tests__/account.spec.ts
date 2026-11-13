import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const post = vi.hoisted(() => vi.fn<(path: string) => Promise<unknown>>())
const signal = vi.hoisted(() => vi.fn<(rpId?: string, id?: string) => Promise<void>>())

vi.mock('../client', async (original) => ({
  ...(await original<typeof import('../client')>()),
  api: { POST: post },
}))
vi.mock('@/lib/webauthn', async (original) => ({
  ...(await original<typeof import('@/lib/webauthn')>()),
  credentialJson: () => ({ id: 'cred-1' }),
  signalUnknownPasskey: signal,
}))

import { signInWithPasskey } from '@/api/account'
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
