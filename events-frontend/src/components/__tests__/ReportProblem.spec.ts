import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'

import { ApiError } from '@/api/client'

const account = vi.hoisted(() => ({
  REPORT_PROBLEMS: [
    'ARTISTS',
    'TITLE',
    'DATE_TIME',
    'STATUS',
    'PRICE_TICKETS',
    'TYPE_GENRE',
    'NOT_AN_EVENT',
    'DUPLICATE',
    'OTHER',
  ],
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchReports: vi.fn<() => Promise<Record<string, unknown>>>(),
  saveReport: vi.fn<(slug: string, report: unknown) => Promise<void>>(),
  withdrawReport: vi.fn<(slug: string) => Promise<void>>(),
}))
vi.mock('@/api/account', () => account)

const { default: ReportProblem } = await import('@/components/ReportProblem.vue')
const { default: ReportProblemForm } = await import('@/components/ReportProblemForm.vue')
const { setSession } = await import('@/composables/useSession')
const { useReports } = await import('@/composables/useReports')

const me = { email: 'ada@example.com', locale: 'en', createdAt: '2026-10-24T10:00:00Z' }
let wrapper: VueWrapper | undefined

beforeEach(() => {
  vi.resetAllMocks()
  account.prepareCsrf.mockResolvedValue()
  account.saveReport.mockResolvedValue()
  account.withdrawReport.mockResolvedValue()
  account.fetchReports.mockResolvedValue({})
  account.fetchSession.mockResolvedValue(null)
  useReports().reports.value = {}
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

async function mountForm() {
  wrapper = mount(ReportProblemForm, { props: { slug: 'tonight' } })
  await flushPromises()
  return wrapper
}

const send = (view: VueWrapper) => view.find('[data-testid="report-send"]')

describe('ReportProblem', () => {
  it('is not there for an account the operator has not allowed, nor signed out', async () => {
    setSession({ ...me, canReport: false } as never)
    wrapper = mount(ReportProblem, { props: { slug: 'tonight', title: 'Tonight' } })
    expect(wrapper.find('[data-testid="report-open"]').exists()).toBe(false)

    setSession(null)
    await flushPromises()
    expect(wrapper.find('[data-testid="report-open"]').exists()).toBe(false)
  })

  it('is there for an allowed account, and says Edit report once reported', async () => {
    setSession({ ...me, canReport: true } as never)
    wrapper = mount(ReportProblem, { props: { slug: 'tonight', title: 'Tonight' } })
    await flushPromises()
    expect(wrapper.find('[data-testid="report-open"]').text()).toBe('Report a problem')

    useReports().reports.value = { tonight: { problems: ['TITLE'], note: '' } }
    await flushPromises()
    expect(wrapper.find('[data-testid="report-open"]').text()).toBe('Edit report')
  })
})

describe('ReportProblemForm', () => {
  it('sends nothing until a box is ticked, then sends the boxes and the note', async () => {
    setSession({ ...me, canReport: true } as never)
    const view = await mountForm()
    expect(send(view).attributes('disabled')).toBeDefined()

    await view.find('[data-testid="report-ARTISTS"]').setValue(true)
    await view.find('[data-testid="report-DATE_TIME"]').setValue(true)
    await view.find('[data-testid="report-note"]').setValue('  Starts at 23:00  ')
    expect(send(view).attributes('disabled')).toBeUndefined()

    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.saveReport).toHaveBeenCalledWith('tonight', {
      problems: ['ARTISTS', 'DATE_TIME'],
      note: '  Starts at 23:00  ',
    })
    expect(useReports().reports.value.tonight).toEqual({
      problems: ['ARTISTS', 'DATE_TIME'],
      note: 'Starts at 23:00',
    })
    expect(view.emitted('done')).toHaveLength(1)
  })

  it('needs a note with Something else', async () => {
    setSession({ ...me, canReport: true } as never)
    const view = await mountForm()
    await view.find('[data-testid="report-OTHER"]').setValue(true)
    expect(send(view).attributes('disabled')).toBeDefined()
    expect(view.text()).toContain('Note (needed for “Something else”)')

    await view.find('[data-testid="report-note"]').setValue('Wrong venue')
    expect(send(view).attributes('disabled')).toBeUndefined()
  })

  it('pre-fills an earlier report, and withdraws it', async () => {
    setSession({ ...me, canReport: true } as never)
    useReports().reports.value = { tonight: { problems: ['STATUS'], note: 'Cancelled' } }
    const view = await mountForm()

    expect((view.find('[data-testid="report-STATUS"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    expect((view.find('[data-testid="report-note"]').element as HTMLTextAreaElement).value).toBe(
      'Cancelled',
    )
    expect(send(view).text()).toBe('Update report')

    await view.find('[data-testid="report-withdraw"]').trigger('click')
    await flushPromises()
    expect(account.withdrawReport).toHaveBeenCalledWith('tonight')
    expect(useReports().reports.value.tonight).toBeUndefined()
  })

  it.each([
    [403, 'This account cannot report problems.'],
    [500, 'That did not work. Please try again.'],
  ])('a %i says why, and keeps the form open', async (status, text) => {
    setSession({ ...me, canReport: true } as never)
    account.saveReport.mockRejectedValue(new ApiError(status, 'refused'))
    const view = await mountForm()
    await view.find('[data-testid="report-TITLE"]').setValue(true)

    await view.find('form').trigger('submit')
    await flushPromises()
    expect(view.find('[role="status"]').text()).toBe(text)
    expect(view.emitted('done')).toBeUndefined()
  })
})
