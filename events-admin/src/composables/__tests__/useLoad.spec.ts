import { describe, expect, it } from 'vitest'

import { useLoad } from '../useLoad'

/** A promise the test settles by hand, to order two loads. */
function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((res, rej) => {
    resolve = res
    reject = rej
  })
  return { promise, resolve, reject }
}

describe('useLoad', () => {
  it('starts loading with the initial data, then holds the answer', async () => {
    const { data, loading, error, reload } = useLoad(async () => ['Badehaus'], [] as string[])
    expect(loading.value).toBe(true)
    expect(data.value).toEqual([])

    await reload()

    expect(data.value).toEqual(['Badehaus'])
    expect(loading.value).toBe(false)
    expect(error.value).toBeNull()
  })

  it('shows the error text and falls back to the initial data', async () => {
    let fail = false
    const { data, error, loading, reload } = useLoad(async () => {
      if (fail) throw new Error('GET /api/admin/festivals: HTTP 502')
      return ['Fusion']
    }, [] as string[])
    await reload()
    fail = true

    await reload()

    expect(error.value).toBe('GET /api/admin/festivals: HTTP 502')
    expect(data.value).toEqual([])
    expect(loading.value).toBe(false)
  })

  it('clears the error on a reload that succeeds', async () => {
    let fail = true
    const { data, error, reload } = useLoad(async () => {
      if (fail) throw 'refused'
      return 7
    }, 0)
    await reload()
    expect(error.value).toBe('refused')
    fail = false

    await reload()

    expect(error.value).toBeNull()
    expect(data.value).toBe(7)
  })

  it('keeps the answer of the latest call when an older one settles later', async () => {
    const answers = [deferred<string>(), deferred<string>()]
    let call = 0
    const { data, loading, error, reload } = useLoad(() => answers[call++]!.promise, '')

    const first = reload()
    const second = reload()
    answers[1]!.resolve('page 2')
    await second
    expect(loading.value).toBe(false)
    answers[0]!.reject(new Error('page 1 failed'))
    await first

    expect(data.value).toBe('page 2')
    expect(error.value).toBeNull()
    expect(loading.value).toBe(false)
  })

  it('stays loading while the latest call runs, whatever an older one answers', async () => {
    const answers = [deferred<string>(), deferred<string>()]
    let call = 0
    const { data, loading, reload } = useLoad(() => answers[call++]!.promise, '')

    const first = reload()
    const second = reload()
    answers[0]!.resolve('page 1')
    await first

    expect(loading.value).toBe(true)
    expect(data.value).toBe('')
    answers[1]!.resolve('page 2')
    await second
    expect(data.value).toBe('page 2')
  })
})
