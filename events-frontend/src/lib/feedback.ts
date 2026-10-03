/**
 * Feedback by mail, so a visitor needs no GitHub account to report wrong data (#378). The role
 * mailbox is already a declared recipient in the privacy notice and the Hetzner AVV; a contact
 * form or an issue created on the visitor's behalf would each be new processing.
 */
import { CONTROLLER } from '@/lib/legal'

/** A `mailto:` link to the role mailbox with the subject and, optionally, the body filled in. */
export function feedbackMailto(subject: string, body?: string): string {
  const params = [`subject=${encodeURIComponent(subject)}`]
  if (body) params.push(`body=${encodeURIComponent(body)}`)
  return `mailto:${CONTROLLER.email}?${params.join('&')}`
}
