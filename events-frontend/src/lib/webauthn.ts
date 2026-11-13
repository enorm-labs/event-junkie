/**
 * The browser half of a passkey (#3888): the BFF's options JSON into what `navigator.credentials` takes, and the
 * answer back into JSON. Safari before 18.4 has neither `PublicKeyCredential.parseCreationOptionsFromJSON` nor
 * `toJSON()`, so both directions are here, and `toJSON()` is used only where it exists.
 */

export function passkeysSupported(): boolean {
  return typeof window !== 'undefined' && typeof window.PublicKeyCredential === 'function'
}

/** Whether the browser offers passkeys in the address field's autofill (conditional mediation). */
export async function autofillSupported(): Promise<boolean> {
  if (!passkeysSupported()) return false
  return (await PublicKeyCredential.isConditionalMediationAvailable?.()) ?? false
}

function decode(value: string): ArrayBuffer {
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/')
  const binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='))
  return Uint8Array.from(binary, (c) => c.charCodeAt(0)).buffer
}

function encode(value: ArrayBuffer): string {
  return btoa(String.fromCharCode(...new Uint8Array(value)))
    .replace(/\+/g, '-')
    .replace(/\//g, '_')
    .replace(/=+$/, '')
}

type Descriptor = { id: string; type?: string; transports?: string[] }

function descriptors(list: Descriptor[] | undefined): PublicKeyCredentialDescriptor[] {
  return (list ?? []).map((d) => ({
    id: decode(d.id),
    type: 'public-key',
    transports: d.transports as AuthenticatorTransport[] | undefined,
  }))
}

export type CreationOptionsJson = {
  rp: { id?: string; name?: string }
  user: { id?: string; name?: string; displayName?: string }
  challenge?: string
  pubKeyCredParams?: { alg?: number; type?: string }[]
  timeout?: number
  excludeCredentials?: Descriptor[]
  authenticatorSelection?: {
    residentKey?: string
    requireResidentKey?: boolean
    userVerification?: string
  }
  attestation?: string
}

export type RequestOptionsJson = {
  challenge?: string
  rpId?: string
  timeout?: number
  userVerification?: string
}

export function creationOptions(json: CreationOptionsJson): PublicKeyCredentialCreationOptions {
  return {
    rp: { id: json.rp.id, name: json.rp.name ?? '' },
    user: {
      id: decode(json.user.id ?? ''),
      name: json.user.name ?? '',
      displayName: json.user.displayName ?? '',
    },
    challenge: decode(json.challenge ?? ''),
    pubKeyCredParams: (json.pubKeyCredParams ?? []).map((p) => ({
      alg: p.alg ?? -7,
      type: 'public-key',
    })),
    timeout: json.timeout,
    excludeCredentials: descriptors(json.excludeCredentials),
    authenticatorSelection: json.authenticatorSelection as AuthenticatorSelectionCriteria,
    attestation: json.attestation as AttestationConveyancePreference,
  }
}

export function requestOptions(json: RequestOptionsJson): PublicKeyCredentialRequestOptions {
  return {
    challenge: decode(json.challenge ?? ''),
    rpId: json.rpId,
    timeout: json.timeout,
    userVerification: json.userVerification as UserVerificationRequirement,
  }
}

/** The answer as `PublicKeyCredential.toJSON()` gives it, which the BFF parses as it is. */
export function credentialJson(credential: PublicKeyCredential): Record<string, unknown> {
  if (typeof credential.toJSON === 'function')
    return credential.toJSON() as unknown as Record<string, unknown>
  const response = credential.response
  const common = { clientDataJSON: encode(response.clientDataJSON) }
  const body =
    response instanceof AuthenticatorAttestationResponse
      ? {
          ...common,
          attestationObject: encode(response.attestationObject),
          transports: response.getTransports?.() ?? [],
        }
      : {
          ...common,
          authenticatorData: encode((response as AuthenticatorAssertionResponse).authenticatorData),
          signature: encode((response as AuthenticatorAssertionResponse).signature),
          userHandle: (response as AuthenticatorAssertionResponse).userHandle
            ? encode((response as AuthenticatorAssertionResponse).userHandle as ArrayBuffer)
            : null,
        }
  return {
    id: credential.id,
    rawId: encode(credential.rawId),
    type: credential.type,
    response: body,
    clientExtensionResults: credential.getClientExtensionResults(),
  }
}

/** What `signalAllAcceptedCredentials` takes: the account's user handle and every credential id the site still accepts. */
export type AcceptedPasskeys = { rpId: string; userId: string; allAcceptedCredentialIds: string[] }

type SignalApi = {
  signalUnknownCredential?: (options: { rpId: string; credentialId: string }) => Promise<void>
  signalAllAcceptedCredentials?: (options: AcceptedPasskeys) => Promise<void>
}

/**
 * Tells the password manager that the site holds no such passkey, so it stops offering it (#4949). Chrome only for now;
 * elsewhere, and on any failure, nothing happens.
 */
export async function signalUnknownPasskey(
  rpId: string | undefined,
  credentialId: string,
): Promise<void> {
  const signal = (PublicKeyCredential as unknown as SignalApi).signalUnknownCredential
  if (!rpId || typeof signal !== 'function') return
  try {
    await signal.call(PublicKeyCredential, { rpId, credentialId })
  } catch {
    // The sign-in already failed; a refused signal adds nothing the visitor can act on.
  }
}

/**
 * Tells the password manager which of the account's passkeys the site still accepts, so it hides the rest (#4954). Chrome
 * only for now; elsewhere, and on any failure, nothing happens.
 */
export async function signalAcceptedPasskeys(accepted: AcceptedPasskeys): Promise<void> {
  const signal = (PublicKeyCredential as unknown as SignalApi).signalAllAcceptedCredentials
  if (typeof signal !== 'function') return
  try {
    await signal.call(PublicKeyCredential, accepted)
  } catch {
    // Housekeeping for the password manager; the page itself is already right.
  }
}

/** The visitor closed the browser's dialog, or another request took its place: nothing to report. */
export function cancelled(e: unknown): boolean {
  return e instanceof DOMException && (e.name === 'NotAllowedError' || e.name === 'AbortError')
}
