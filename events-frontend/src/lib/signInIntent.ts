/**
 * Why a visitor came to sign in (#4968): the button they pressed. Read only by the sign-in page, for its
 * heading and the order of its list, so it never reaches the BFF. Any other value is ignored.
 */
export const SIGN_IN_INTENTS = ['follow', 'rsvp', 'save'] as const

export type SignInIntent = (typeof SIGN_IN_INTENTS)[number]

export function signInIntent(value: unknown): SignInIntent | null {
  return SIGN_IN_INTENTS.find((intent) => intent === value) ?? null
}
