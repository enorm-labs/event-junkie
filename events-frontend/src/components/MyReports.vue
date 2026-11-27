<script setup lang="ts">
/**
 * The account's open problem reports on its account page (#5308): which page, what it ticked, and
 * when. A report goes once the operator resolves it, and the bell then says what came of it.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import ErrorLine from '@/components/ErrorLine.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { fetchReportList } from '@/api/account'
import type { AccountReport } from '@/api/types'
import { useLocalePath } from '@/composables/useLocalePath'
import { berlinDateOf, formatLongDate } from '@/lib/format'
import { CARD_LIST_CLASS } from '@/lib/utils'

const { t, te, locale } = useI18n()
const localePath = useLocalePath()

const reports = ref<AccountReport[] | null>(null)
const problem = ref('')

onMounted(async () => {
  try {
    reports.value = await fetchReportList()
  } catch {
    problem.value = t('account.myReports.failed')
  }
})

/** `events` to `event`: the form's label keys go by the singular kind. */
const singular = (kind: string) => kind.replace(/s$/, '')

const date = (at?: string) => (at ? formatLongDate(berlinDateOf(at), locale.value) : '')

/** The ticked boxes by their form labels. A box the form no longer shows, and the hint, are left out. */
function problems(report: AccountReport): string {
  const kind = singular(report.kind ?? '')
  return (report.problems ?? [])
    .map((p) =>
      p === 'OTHER'
        ? t('account.myReports.other')
        : te(`account.report.${kind}.problems.${p}`)
          ? t(`account.report.${kind}.problems.${p}`)
          : '',
    )
    .filter(Boolean)
    .join(', ')
}
</script>

<template>
  <section class="space-y-3" data-testid="my-reports">
    <SectionLabel as="h2">{{ t('account.myReports.title') }}</SectionLabel>
    <ErrorLine v-if="problem">{{ problem }}</ErrorLine>
    <template v-else-if="reports">
      <ul v-if="reports.length" :class="CARD_LIST_CLASS">
        <li v-for="report in reports" :key="`${report.kind}/${report.slug}`" class="py-3">
          <RouterLink
            :to="localePath(`/${report.kind}/${report.slug}`)"
            class="text-body font-medium underline-offset-4 hover:underline"
          >
            {{ report.title }}
          </RouterLink>
          <p class="text-meta text-muted-foreground">
            {{ t(`account.myReports.kind.${report.kind}`) }} ·
            {{ date(report.updatedAt) }}
          </p>
          <p v-if="problems(report)" class="text-meta">{{ problems(report) }}</p>
        </li>
      </ul>
      <p class="text-body text-muted-foreground">
        {{ t(reports.length ? 'account.myReports.intro' : 'account.myReports.empty') }}
      </p>
    </template>
  </section>
</template>
