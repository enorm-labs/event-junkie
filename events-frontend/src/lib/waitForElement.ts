/**
 * Resolves once `selector` matches an element, checking once per animation frame, or with `null`
 * after `timeoutMs`. The router needs it for a hash into a lazily loaded page (`localisedView`):
 * the page renders after the navigation resolves, so the anchor is not there when it scrolls.
 */
export function waitForElement(selector: string, timeoutMs: number): Promise<Element | null> {
  const deadline = performance.now() + timeoutMs
  return new Promise((resolve) => {
    const check = () => {
      const element = document.querySelector(selector)
      if (element || performance.now() >= deadline) resolve(element)
      else requestAnimationFrame(check)
    }
    check()
  })
}
