<script setup lang="ts">
/**
 * The form inside "Report a problem" (#4399, #5603): the kind's problems as boxes, on an event
 * the image hint set apart, a note, Send. A box or a note is enough; the image hint alone says
 * nothing. An earlier report keeps the names of an older form, which get no box, when it is
 * saved again.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import {
  REPORT_HINT,
  REPORT_PROBLEMS_BY_KIND,
  reportKey,
  type OpenReport,
  type ReportKind,
  type ReportProblem,
} from '@/api/account'
import { ApiError } from '@/api/client'
import { useReports } from '@/composables/useReports'
import { CHECKBOX_CLASS, cn, FIELD_CLASS } from '@/lib/utils'

const props = defineProps<{ kind: ReportKind; slug: string }>()
const emit = defineEmits<{ done: [] }>()

const MAX_NOTE = 500

const { t } = useI18n()
const { reports, save, withdraw } = useReports()

/** The i18n group of each kind's box labels and note placeholder. */
const LABELS: Record<ReportKind, string> = {
  events: 'event',
  artists: 'artist',
  promoters: 'promoter',
  venues: 'venue',
}

const options = computed(() => REPORT_PROBLEMS_BY_KIND[props.kind])
const labels = computed(() => `account.report.${LABELS[props.kind]}`)
const withHint = computed(() => props.kind === 'events')
const earlier = computed<OpenReport | undefined>(
  () => reports.value[reportKey(props.kind, props.slug)],
)
const shown = (p: ReportProblem) => REPORT_PROBLEMS_BY_KIND[props.kind].includes(p)
const before = earlier.value?.problems ?? []
const problems = ref<ReportProblem[]>(before.filter(shown))
const hint = ref(before.includes(REPORT_HINT))
const kept = before.filter((p) => !shown(p) && p !== REPORT_HINT)
const note = ref(earlier.value?.note ?? '')
const busy = ref(false)
const problem = ref('')

const canSend = computed(
  () => (problems.value.length > 0 || kept.length > 0 || note.value.trim() !== '') && !busy.value,
)

function message(e: unknown): string {
  if (e instanceof ApiError && e.status === 403) return t('account.report.forbidden')
  return t('account.report.failed')
}

async function run(action: () => Promise<void>) {
  busy.value = true
  problem.value = ''
  try {
    await action()
    emit('done')
  } catch (e) {
    problem.value = message(e)
  } finally {
    busy.value = false
  }
}

const send = () =>
  run(() =>
    save(props.kind, props.slug, {
      problems: [...problems.value, ...(hint.value ? [REPORT_HINT as ReportProblem] : []), ...kept],
      note: note.value,
    }),
  )
const remove = () => run(() => withdraw(props.kind, props.slug))
</script>

<template>
  <form class="space-y-4" @submit.prevent="canSend && send()">
    <fieldset class="space-y-2">
      <legend class="mb-2 text-body text-muted-foreground">{{ t('account.report.legend') }}</legend>
      <label v-for="option in options" :key="option" class="flex items-start gap-2 text-body">
        <input
          v-model="problems"
          :data-testid="`report-${option}`"
          :class="['mt-1', CHECKBOX_CLASS]"
          :value="option"
          type="checkbox"
        />
        <span>{{ t(`${labels}.problems.${option}`) }}</span>
      </label>
    </fieldset>

    <label v-if="withHint" class="flex items-start gap-2 border-t border-border pt-4 text-body">
      <input
        v-model="hint"
        :class="['mt-1', CHECKBOX_CLASS]"
        :data-testid="`report-${REPORT_HINT}`"
        type="checkbox"
      />
      <span>{{ t('account.report.hint') }}</span>
    </label>

    <label class="block space-y-1 text-body">
      <span class="block">{{ t('account.report.note') }}</span>
      <textarea
        v-model="note"
        :placeholder="t(`${labels}.notePlaceholder`)"
        :class="cn(FIELD_CLASS, 'h-auto min-h-20 w-full py-1')"
        :maxlength="MAX_NOTE"
        data-testid="report-note"
        rows="3"
      />
    </label>

    <div class="flex flex-wrap items-center gap-3">
      <Button :disabled="!canSend" data-testid="report-send" type="submit">
        {{ t(earlier ? 'account.report.update' : 'account.report.send') }}
      </Button>
      <Button
        v-if="earlier"
        :disabled="busy"
        data-testid="report-withdraw"
        type="button"
        variant="outline"
        @click="remove"
      >
        {{ t('account.report.withdraw') }}
      </Button>
      <span class="text-body text-destructive empty:hidden" role="status">{{ problem }}</span>
    </div>
  </form>
</template>
