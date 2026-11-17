/** `smooth`, unless the OS asks for reduced motion. jsdom has no `matchMedia`; there motion is allowed. */
export function scrollMotion(): ScrollBehavior {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
}
