<script setup lang="ts">
/**
 * "Report a problem" in the header row of an event, act, promoter or venue page (#4399, #5603,
 * #5605). Signed out, a link to sign-in that comes back with the sheet open; an account the
 * operator withdrew it from (#5604) sees nothing. It stores a report and nothing more; the
 * operator works the queue. The thanks goes to `status`, so the row's live region says it, as
 * Share's outcome does.
 */
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { Flag, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import ReportProblemForm from '@/components/ReportProblemForm.vue'
import { reportKey, type ReportKind } from '@/api/account'
import { useLocalePath } from '@/composables/useLocalePath'
import { useReports } from '@/composables/useReports'
import { useSession } from '@/composables/useSession'
import { useSignInAction } from '@/composables/useSignInAction'
import { signInReturn } from '@/lib/signInNext'

const props = defineProps<{ kind: ReportKind; slug: string; title: string }>()
const status = defineModel<string>('status', { default: '' })

const { t } = useI18n()
const route = useRoute()
const localePath = useLocalePath()
const { account } = useSession()
const { reports } = useReports()
const signInAction = useSignInAction()

const open = ref(false)
const reported = computed(() => Boolean(reports.value[reportKey(props.kind, props.slug)]))

// Back from the sign-in this link led to: the sheet opens, as the press would have.
watch(
  () => account.value?.canReport,
  (can) => {
    if (can && signInAction.take('report')) open.value = true
  },
  { immediate: true },
)

function done() {
  open.value = false
  status.value = reported.value ? t('account.report.thanks') : ''
}
</script>

<template>
  <Button v-if="account === null" as-child variant="outline">
    <RouterLink
      :to="{
        path: localePath('/sign-in'),
        query: { next: signInReturn(route.path, 'report'), intent: 'report' },
      }"
      data-testid="report-sign-in"
      rel="nofollow"
    >
      <Flag aria-hidden="true" />
      {{ t('account.report.open') }}
    </RouterLink>
  </Button>
  <template v-else-if="account?.canReport">
    <Button data-testid="report-open" variant="outline" @click="open = true">
      <Flag aria-hidden="true" />
      {{ t(reported ? 'account.report.edit' : 'account.report.open') }}
    </Button>

    <Sheet v-model:open="open">
      <SheetContent :aria-describedby="undefined">
        <div class="flex items-center justify-between gap-3 border-b border-border px-4 py-3">
          <SheetTitle>{{ t('account.report.title', { title }) }}</SheetTitle>
          <Button
            :aria-label="t('account.report.close')"
            size="icon"
            type="button"
            variant="ghost"
            @click="open = false"
          >
            <X aria-hidden="true" />
          </Button>
        </div>
        <div class="flex-1 overflow-y-auto p-4">
          <ReportProblemForm :kind="kind" :slug="slug" @done="done" />
        </div>
      </SheetContent>
    </Sheet>
  </template>
</template>
