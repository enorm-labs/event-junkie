import { afterEach, describe, expect, it } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h, onMounted, watch } from 'vue'

import App from '@/App.vue'
import { useAsync } from '@/composables/useAsync'

/**
 * The footer renders once the routed view has its content (#2567). A footer that is there first
 * moves when the content arrives above it, and on a short page that move is the layout shift.
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

let first = deferred<string>()
let second = deferred<string>()

const StaticView = defineComponent({ render: () => h('main', 'static') })

/** Loads on mount, as every data view does through `useAsync`. */
const DataView = defineComponent({
  setup() {
    const { data, run } = useAsync(() => first.promise)
    onMounted(run)
    return () => h('main', data.value ?? 'loading')
  },
})

/** Starts its second request from a watcher on the first answer. */
const ChainedView = defineComponent({
  setup() {
    const entity = useAsync(() => first.promise)
    const related = useAsync(() => second.promise)
    onMounted(entity.run)
    watch(entity.data, () => related.run())
    return () => h('main', related.data.value ?? 'loading')
  },
})

const stubs = {
  AppFooter: { template: '<footer data-testid="footer" />' },
  GlobalSearch: true,
  LocaleMenu: true,
  DisplaySettings: true,
  HeaderMenu: true,
}

let wrapper: VueWrapper | undefined
let router: Router

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/static', component: StaticView },
      { path: '/:locale(en|de)/data', component: DataView },
      { path: '/:locale(en|de)/chained', component: ChainedView },
    ],
  })
  await router.push(path)
  wrapper = mount(App, { global: { plugins: [router], stubs } })
  await flushPromises()
}

function footerShown(): boolean {
  return wrapper!.find('[data-testid="footer"]').exists()
}

describe('App shell footer', () => {
  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    first = deferred<string>()
    second = deferred<string>()
  })

  it('renders at once on a view that loads nothing', async () => {
    await mountAt('/en/static')

    expect(footerShown()).toBe(true)
  })

  it('waits for the view content, then renders below it', async () => {
    await mountAt('/en/data')
    expect(footerShown()).toBe(false)

    first.resolve('content')
    await flushPromises()
    expect(wrapper!.text()).toContain('content')
    expect(footerShown()).toBe(true)
  })

  it('renders under an error or a 404, which is the content of that page', async () => {
    await mountAt('/en/data')

    first.reject(new Error('gone'))
    await flushPromises()
    expect(footerShown()).toBe(true)
  })

  it('waits for a request the first answer starts', async () => {
    await mountAt('/en/chained')

    first.resolve('entity')
    await flushPromises()
    expect(footerShown()).toBe(false)

    second.resolve('related')
    await flushPromises()
    expect(footerShown()).toBe(true)
  })

  it('leaves on a navigation to a view that loads, and returns with its content', async () => {
    await mountAt('/en/static')
    expect(footerShown()).toBe(true)

    await router.push('/en/data')
    await flushPromises()
    expect(footerShown()).toBe(false)

    first.resolve('content')
    await flushPromises()
    expect(footerShown()).toBe(true)
  })
})
