import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'

const route = reactive<{ query: Record<string, string | string[]> }>({ query: {} })
const push = vi.fn<(location: unknown) => void>()

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

const { useEventFilters } = await import('@/composables/useEventFilters')

describe('useEventFilters', () => {
  beforeEach(() => {
    route.query = {}
    push.mockClear()
  })

  it('reads one event type or a repeated one as a list', () => {
    const { filters, queryList } = useEventFilters()
    expect(filters.value.eventType).toBeUndefined()

    route.query = { eventType: 'PARTY' }
    expect(filters.value.eventType).toEqual(['PARTY'])

    route.query = { eventType: ['CONCERT', '', 'PARTY'] }
    expect(queryList('eventType')).toEqual(['CONCERT', 'PARTY'])
  })

  it('drops an empty list from the URL and resets the page', () => {
    route.query = { eventType: ['CONCERT', 'PARTY'], page: '3', venue: 'lido' }
    useEventFilters().applyFilters({ eventType: [] })
    expect(push).toHaveBeenCalledWith({ query: { venue: 'lido' } })
  })
})
