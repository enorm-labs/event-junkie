import { beforeEach, describe, expect, it, vi } from 'vitest'
import { reactive } from 'vue'

const route = reactive<{ query: Record<string, string | string[]> }>({ query: {} })
const push = vi.fn<(location: unknown) => void>()

vi.mock('vue-router', () => ({
  useRoute: () => route,
  useRouter: () => ({ push }),
}))

vi.mock('vue-i18n', () => ({
  useI18n: () => ({ t: (key: string) => key }),
}))

const { useListQuery } = await import('@/composables/useListQuery')

describe('useListQuery', () => {
  beforeEach(() => {
    route.query = {}
    push.mockClear()
  })

  it('reads a single value only when it appears once', () => {
    const { queryString } = useListQuery()
    expect(queryString('q')).toBe('')

    route.query = { q: 'berghain' }
    expect(queryString('q')).toBe('berghain')

    route.query = { q: ['a', 'b'] }
    expect(queryString('q')).toBe('')
  })

  it('reads one value or a repeated one as a list, without empty values', () => {
    const { queryList } = useListQuery()
    expect(queryList('district')).toEqual([])

    route.query = { district: 'kreuzberg' }
    expect(queryList('district')).toEqual(['kreuzberg'])

    route.query = { district: ['kreuzberg', '', 'mitte'] }
    expect(queryList('district')).toEqual(['kreuzberg', 'mitte'])
  })

  it('drops an empty value and an empty list from the URL and resets the page', () => {
    route.query = { q: 'club', district: ['mitte'], sort: 'name,desc', page: '2' }
    useListQuery().applyFilters({ q: '', district: [] })
    expect(push).toHaveBeenCalledWith({ query: { sort: 'name,desc' } })
  })

  it.each([
    [undefined, '', ['name,asc']],
    ['name,desc', 'name,desc', ['name,desc']],
    ['upcomingEvents,desc', 'upcomingEvents,desc', ['upcomingEvents,desc']],
    ['name,asc', '', ['name,asc']],
    ['price,desc', '', ['name,asc']],
  ])('reads ?sort=%s as %j and sends %j', (requested, sort, sent) => {
    route.query = requested ? { sort: requested } : {}
    const query = useListQuery()
    expect(query.sort.value).toBe(sort)
    expect(query.sortParam.value).toEqual(sent)
  })

  it('offers A–Z and Z–A as one control, then the upcoming-events order', () => {
    expect(useListQuery().sortOptions.value).toEqual([
      {
        directions: [
          { value: '', ascending: true, label: 'common.sort.az' },
          { value: 'name,desc', ascending: false, label: 'common.sort.za' },
        ],
      },
      { label: 'common.sort.upcoming', value: 'upcomingEvents,desc' },
    ])
  })
})
