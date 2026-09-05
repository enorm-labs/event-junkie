import { describe, expect, it } from 'vitest'

import { ApiError } from '@/api/client'
import { useAsync } from '@/composables/useAsync'

/**
 * The cache exists for one reason: a view navigated back to has to have its height before the
 * router restores the scroll position, so a remembered answer must land synchronously in `run()`
 * — not after a microtask, which is already too late (#1111).
 */

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useAsync', () => {
  it('reports loading until the loader answers, then exposes the data', async () => {
    const load = deferred<string[]>()
    const { data, loading, run } = useAsync(() => load.promise)

    const running = run()
    expect(loading.value).toBe(true)
    expect(data.value).toBeNull()

    load.resolve(['a'])
    await running
    expect(loading.value).toBe(false)
    expect(data.value).toEqual(['a'])
  })

  it('paints a remembered answer synchronously and then refreshes it', async () => {
    const key = () => 'spec:remembered'
    await useAsync(() => Promise.resolve(['stale']), undefined, key).run()

    const load = deferred<string[]>()
    const { data, loading, run } = useAsync(() => load.promise, undefined, key)
    const running = run()

    // Before any await: the height the router will scroll to comes from this value.
    expect(data.value).toEqual(['stale'])
    expect(loading.value).toBe(false)

    load.resolve(['fresh'])
    await running
    expect(data.value).toEqual(['fresh'])
  })

  it('keeps answers apart by key and remembers nothing without one', async () => {
    await useAsync(
      () => Promise.resolve('one'),
      undefined,
      () => 'spec:one',
    ).run()
    await useAsync(() => Promise.resolve('anon')).run()

    const other = useAsync(
      () => deferred<string>().promise,
      undefined,
      () => 'spec:two',
    )
    void other.run()
    expect(other.data.value).toBeNull()
    expect(other.loading.value).toBe(true)
  })

  it('reports a failed refresh as an error rather than keeping the remembered answer', async () => {
    const key = () => 'spec:fails'
    await useAsync(() => Promise.resolve('was fine'), undefined, key).run()

    const { data, error, notFound, run } = useAsync<string>(
      () => Promise.reject(new ApiError(404, 'gone')),
      undefined,
      key,
    )
    await run()

    expect(data.value).toBeNull()
    expect(notFound.value).toBe(true)
    expect(error.value).not.toBeNull()
  })
})
