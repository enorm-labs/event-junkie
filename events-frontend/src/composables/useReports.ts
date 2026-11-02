import { ref, watch } from 'vue'
import {
  fetchReports,
  type OpenReport,
  prepareCsrf,
  saveReport,
  withdrawReport,
} from '@/api/account'
import { useSession } from './useSession'

/**
 * The account's open problem reports (#4399), so the event page says "Edit report" and the form
 * pre-fills. Module state and one request per visit, as `useRsvps` does. An account the operator
 * has not allowed asks nothing.
 */
const reports = ref<Record<string, OpenReport>>({})
let loaded: Promise<void> | null = null
let watching = false

async function load(): Promise<void> {
  try {
    reports.value = await fetchReports()
  } catch {
    // Without the list the button still works; it just does not know about an earlier report.
    reports.value = {}
  }
}

export function useReports() {
  const { account, ready } = useSession()
  if (!watching) {
    watching = true
    watch(account, (now, before) => {
      if (now === undefined || now?.email === before?.email) return
      reports.value = {}
      loaded = now?.canReport ? load() : Promise.resolve()
    })
  }
  loaded ??= ready().then(() => (account.value?.canReport ? load() : undefined))

  async function save(slug: string, report: OpenReport): Promise<void> {
    await prepareCsrf()
    await saveReport(slug, report)
    reports.value = { ...reports.value, [slug]: { ...report, note: report.note.trim() } }
  }

  async function withdraw(slug: string): Promise<void> {
    await prepareCsrf()
    await withdrawReport(slug)
    const next = { ...reports.value }
    delete next[slug]
    reports.value = next
  }

  return { reports, save, withdraw, ready: () => loaded ?? Promise.resolve() }
}
