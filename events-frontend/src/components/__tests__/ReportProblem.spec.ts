import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import type { ReportKind } from '@/api/account'
import { ApiError } from '@/api/client'

const account = vi.hoisted(() => ({
  prepareCsrf: vi.fn<() => Promise<void>>(),
  fetchSession: vi.fn<() => Promise<unknown>>(),
  fetchReports: vi.fn<() => Promise<Record<string, unknown>>>(),
  saveReport: vi.fn<(kind: string, slug: string, report: unknown) => Promise<void>>(),
  withdrawReport: vi.fn<(kind: string, slug: string) => Promise<void>>(),
}))
// The box lists and `reportKey` stay real, so the form shows what the BFF accepts.
vi.mock('@/api/account', async (importOriginal) => ({
  ...(await importOriginal<object>()),
  ...account,
}))

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

async function mountForm(kind: ReportKind = 'events', slug = 'tonight') {
  wrapper = mount(ReportProblemForm, { props: { kind, slug } })
  await flushPromises()
  return wrapper
}

const send = (view: VueWrapper) => view.find('[data-testid="report-send"]')

async function mountButton(path = '/en/events/tonight') {
  const router: Router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:any(.*)*', component: { render: () => null } }],
  })
  await router.push(path)
  wrapper = mount(ReportProblem, {
    props: { kind: 'events', slug: 'tonight', title: 'Tonight' },
    global: { plugins: [router] },
  })
  await flushPromises()
  return { view: wrapper, router }
}

describe('ReportProblem', () => {
  it('signed out, is a link to sign-in that comes back to report', async () => {
    setSession(null)
    const { view } = await mountButton()

    const link = view.find('[data-testid="report-sign-in"]')
    expect(link.text()).toBe('Report a problem')
    const href = new URL(link.attributes('href') ?? '', 'https://example.de')
    expect(href.pathname).toBe('/en/sign-in')
    expect(href.searchParams.get('next')).toBe('/en/events/tonight?do=report')
    expect(href.searchParams.get('intent')).toBe('report')
    expect(view.find('[data-testid="report-open"]').exists()).toBe(false)
  })

  it('is not there for an account the operator withdrew reporting from', async () => {
    setSession({ ...me, canReport: false } as never)
    const { view } = await mountButton()
    expect(view.find('[data-testid="report-open"]').exists()).toBe(false)
    expect(view.find('[data-testid="report-sign-in"]').exists()).toBe(false)
  })

  it('is there for a signed-in account, and says Edit report once reported', async () => {
    setSession({ ...me, canReport: true } as never)
    const { view } = await mountButton()
    expect(view.find('[data-testid="report-open"]').text()).toBe('Report a problem')

    useReports().reports.value = { 'events/tonight': { problems: ['TEXT'], note: '' } }
    await flushPromises()
    expect(view.find('[data-testid="report-open"]').text()).toBe('Edit report')
  })

  it('back from sign-in, opens the sheet once and drops do from the URL', async () => {
    setSession({ ...me, canReport: true } as never)
    const { view, router } = await mountButton('/en/events/tonight?do=report')

    expect(view.findComponent(ReportProblemForm).exists()).toBe(true)
    expect(router.currentRoute.value.query.do).toBeUndefined()
  })

  it('says thanks on the status it is bound to once a report is sent', async () => {
    setSession({ ...me, canReport: true } as never)
    const { view } = await mountButton()
    await view.find('[data-testid="report-open"]').trigger('click')
    await flushPromises()

    useReports().reports.value = { 'events/tonight': { problems: ['TEXT'], note: '' } }
    view.findComponent(ReportProblemForm).vm.$emit('done')
    await flushPromises()
    expect(view.emitted('update:status')?.slice(-1)[0]).toEqual([
      'Thanks! We check reports within a few days. You can change or withdraw yours here until then.',
    ])
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
    expect(account.saveReport).toHaveBeenCalledWith('events', 'tonight', {
      problems: ['ARTISTS', 'DATE_TIME'],
      note: '  Starts at 23:00  ',
    })
    expect(useReports().reports.value['events/tonight']).toEqual({
      problems: ['ARTISTS', 'DATE_TIME'],
      note: 'Starts at 23:00',
    })
    expect(view.emitted('done')).toHaveLength(1)
  })

  it('sends a note alone, and never asks for one', async () => {
    setSession({ ...me, canReport: true } as never)
    const view = await mountForm()
    expect(view.text()).toContain('Note or question (optional)')

    await view.find('[data-testid="report-note"]').setValue('   ')
    expect(send(view).attributes('disabled')).toBeDefined()
    await view.find('[data-testid="report-note"]').setValue('Is this really a party?')
    expect(send(view).attributes('disabled')).toBeUndefined()

    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.saveReport).toHaveBeenCalledWith('events', 'tonight', {
      problems: [],
      note: 'Is this really a party?',
    })
  })

  it('sends the image hint only with a problem or a note', async () => {
    setSession({ ...me, canReport: true } as never)
    const view = await mountForm()
    await view.find('[data-testid="report-ON_IMAGE"]').setValue(true)
    expect(send(view).attributes('disabled')).toBeDefined()

    await view.find('[data-testid="report-note"]').setValue('See flyer')
    expect(send(view).attributes('disabled')).toBeUndefined()
    await view.find('[data-testid="report-note"]').setValue('')
    await view.find('[data-testid="report-GENRE"]').setValue(true)
    expect(send(view).attributes('disabled')).toBeUndefined()

    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.saveReport).toHaveBeenCalledWith('events', 'tonight', {
      problems: ['GENRE', 'ON_IMAGE'],
      note: '',
    })
  })

  it('keeps the names of an older form on save, and shows no box for them', async () => {
    setSession({ ...me, canReport: true } as never)
    useReports().reports.value = {
      'events/tonight': { problems: ['TYPE_GENRE', 'ON_IMAGE', 'QUESTION'], note: '' },
    }
    const view = await mountForm()
    expect(view.find('[data-testid="report-TYPE_GENRE"]').exists()).toBe(false)
    expect(view.find('[data-testid="report-QUESTION"]').exists()).toBe(false)
    expect((view.find('[data-testid="report-ON_IMAGE"]').element as HTMLInputElement).checked).toBe(
      true,
    )
    expect(send(view).attributes('disabled')).toBeUndefined()

    await view.find('[data-testid="report-EVENT_TYPE"]').setValue(true)
    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.saveReport).toHaveBeenCalledWith('events', 'tonight', {
      problems: ['EVENT_TYPE', 'ON_IMAGE', 'TYPE_GENRE', 'QUESTION'],
      note: '',
    })
  })

  it('pre-fills an earlier report, and withdraws it', async () => {
    setSession({ ...me, canReport: true } as never)
    useReports().reports.value = {
      'events/tonight': { problems: ['STATUS'], note: 'Cancelled' },
    }
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
    expect(account.withdrawReport).toHaveBeenCalledWith('events', 'tonight')
    expect(useReports().reports.value['events/tonight']).toBeUndefined()
  })

  it("shows a venue's own boxes and no image hint, and sends them for the venue", async () => {
    setSession({ ...me, canReport: true } as never)
    useReports().reports.value = { 'events/club': { problems: ['STATUS'], note: '' } }
    const view = await mountForm('venues', 'club')
    expect(view.findAll('input[type="checkbox"]').map((b) => b.attributes('data-testid'))).toEqual([
      'report-NAME',
      'report-ADDRESS',
      'report-LINKS',
      'report-TEXT',
      'report-CLOSED',
    ])
    expect(view.text()).toContain('Address or map pin wrong')
    expect(send(view).text()).toBe('Send report')

    await view.find('[data-testid="report-ADDRESS"]').setValue(true)
    await view.find('form').trigger('submit')
    await flushPromises()
    expect(account.saveReport).toHaveBeenCalledWith('venues', 'club', {
      problems: ['ADDRESS'],
      note: '',
    })
    expect(useReports().reports.value['venues/club']).toEqual({ problems: ['ADDRESS'], note: '' })
  })

  it.each([
    [403, 'This account cannot report problems.'],
    [429, 'You have 200 open reports. Withdraw one, or wait until we have checked some.'],
    [500, 'That did not work. Please try again.'],
  ])('a %i says why, and keeps the form open', async (status, text) => {
    setSession({ ...me, canReport: true } as never)
    account.saveReport.mockRejectedValue(new ApiError(status, 'refused'))
    const view = await mountForm()
    await view.find('[data-testid="report-TEXT"]').setValue(true)

    await view.find('form').trigger('submit')
    await flushPromises()
    expect(view.find('[role="status"]').text()).toBe(text)
    expect(view.emitted('done')).toBeUndefined()
  })
})
