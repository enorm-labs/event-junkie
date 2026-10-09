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

  it('reads one genre family or a repeated one as a list', () => {
    const { filters } = useEventFilters()
    route.query = { family: 'electronic' }
    expect(filters.value.family).toEqual(['electronic'])

    route.query = { family: ['electronic', 'hip-hop'] }
    expect(filters.value.family).toEqual(['electronic', 'hip-hop'])
  })

  it('sends the language only while the type filter selects only types that carry one', () => {
    const { filters } = useEventFilters()
    route.query = { language: 'en' }
    expect(filters.value.language).toBeUndefined()

    route.query = { eventType: ['COMEDY', 'CONCERT'], language: 'en' }
    expect(filters.value.language).toBeUndefined()

    route.query = { eventType: ['COMEDY', 'READING'], language: ['en', 'de'] }
    expect(filters.value.language).toEqual(['en', 'de'])
  })

  it('reads one venue type or a repeated one as a list', () => {
    const { filters } = useEventFilters()
    expect(filters.value.venueType).toBeUndefined()

    route.query = { venueType: 'club' }
    expect(filters.value.venueType).toEqual(['club'])

    route.query = { venueType: ['club', '', 'bar'] }
    expect(filters.value.venueType).toEqual(['club', 'bar'])
  })

  it('drops an empty list from the URL and resets the page', () => {
    route.query = { eventType: ['CONCERT', 'PARTY'], page: '3', venue: 'lido' }
    useEventFilters().applyFilters({ eventType: [] })
    expect(push).toHaveBeenCalledWith({ query: { venue: 'lido' } })
  })
})
