import { afterEach, describe, expect, it, vi } from 'vitest'

import { cancelled, creationOptions, credentialJson, requestOptions } from '@/lib/webauthn'

const bytes = (...values: number[]) => Uint8Array.from(values).buffer

afterEach(() => vi.unstubAllGlobals())

describe('webauthn', () => {
  it('decodes the options base64url, also where it needs padding and holds - and _', () => {
    const options = creationOptions({
      rp: { id: 'event-junkie.de', name: 'Event Junkie' },
      user: { id: '-_8', name: 'ada@example.com', displayName: 'ada@example.com' },
      challenge: 'AQID',
      pubKeyCredParams: [{ alg: -7 }],
      excludeCredentials: [{ id: 'BAU' }],
      authenticatorSelection: { residentKey: 'required', userVerification: 'required' },
    })
    expect([...new Uint8Array(options.user.id as ArrayBuffer)]).toEqual([0xfb, 0xff])
    expect([...new Uint8Array(options.challenge as ArrayBuffer)]).toEqual([1, 2, 3])
    expect([...new Uint8Array(options.excludeCredentials![0]!.id as ArrayBuffer)]).toEqual([4, 5])
    expect(options.authenticatorSelection?.userVerification).toBe('required')
    expect(requestOptions({ challenge: 'AQID', rpId: 'event-junkie.de' }).rpId).toBe(
      'event-junkie.de',
    )
  })

  it('writes an assertion as toJSON() would, where the browser has no toJSON()', () => {
    class AuthenticatorAttestationResponse {}
    vi.stubGlobal('AuthenticatorAttestationResponse', AuthenticatorAttestationResponse)
    const credential = {
      id: '-_8',
      rawId: bytes(0xfb, 0xff),
      type: 'public-key',
      response: {
        clientDataJSON: bytes(1),
        authenticatorData: bytes(2),
        signature: bytes(3),
        userHandle: bytes(4),
      },
      getClientExtensionResults: () => ({}),
    } as unknown as PublicKeyCredential
    expect(credentialJson(credential)).toEqual({
      id: '-_8',
      rawId: '-_8',
      type: 'public-key',
      response: {
        clientDataJSON: 'AQ',
        authenticatorData: 'Ag',
        signature: 'Aw',
        userHandle: 'BA',
      },
      clientExtensionResults: {},
    })
  })

  it('counts a closed dialog and an aborted autofill as cancelled, nothing else', () => {
    expect(cancelled(new DOMException('x', 'NotAllowedError'))).toBe(true)
    expect(cancelled(new DOMException('x', 'AbortError'))).toBe(true)
    expect(cancelled(new DOMException('x', 'SecurityError'))).toBe(false)
    expect(cancelled(new Error('x'))).toBe(false)
  })
})
