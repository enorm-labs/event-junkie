import { type MaybeRefOrGetter, onScopeDispose, toValue, watchEffect } from 'vue'

import { INJECTED_ATTRIBUTE, type JsonLd, jsonLdText } from '@/lib/structuredData'

/**
 * Publishes JSON-LD documents into a `<script type="application/ld+json">` for the current view.
 *
 * Pass a getter. Detail views load their entity **after** mounting, so the documents are null on
 * first render and arrive later; `watchEffect` republishes when they do. Navigating between two
 * events keeps the same component mounted and only changes the slug, which is the case that would
 * otherwise leave the previous event's data describing the new page.
 *
 * The element is removed when the effect scope is disposed, so nothing leaks across routes. A page
 * the injector served already carries one, marked `data-injected` (ADR-044): the first view to
 * set up takes that element over, so a booted page never holds two.
 */
export function useStructuredData(documents: MaybeRefOrGetter<JsonLd | JsonLd[] | null>): void {
  let script = document.head.querySelector<HTMLScriptElement>(`script[${INJECTED_ATTRIBUTE}]`)
  script?.removeAttribute(INJECTED_ATTRIBUTE)

  const remove = () => {
    script?.remove()
    script = null
  }

  watchEffect(() => {
    const value = toValue(documents)
    const list = (Array.isArray(value) ? value : [value]).filter(Boolean) as JsonLd[]

    if (!list.length) return remove()

    script ??= document.head.appendChild(
      Object.assign(document.createElement('script'), { type: 'application/ld+json' }),
    )
    // `textContent` keeps the browser from parsing scraped third-party text as markup.
    script.textContent = jsonLdText(list)
  })

  onScopeDispose(remove)
}
