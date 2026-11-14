<script setup lang="ts">
/**
 * The form inside "Report a problem" (#4399): the common problems as boxes, a note, Send. The
 * note is required with "Something else" and a question, because those boxes alone say nothing.
 * "On the image" is a hint, so it never goes alone (#4978).
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/components/ui/button'
import { REPORT_PROBLEMS, type OpenReport, type ReportProblem } from '@/api/account'
import { ApiError } from '@/api/client'
import { useReports } from '@/composables/useReports'
import { CHECKBOX_CLASS, cn, FIELD_CLASS } from '@/lib/utils'

const props = defineProps<{ slug: string }>()
const emit = defineEmits<{ done: [] }>()

const MAX_NOTE = 500

const { t } = useI18n()
const { reports, save, withdraw } = useReports()

const earlier = computed<OpenReport | undefined>(() => reports.value[props.slug])
const problems = ref<ReportProblem[]>([...(earlier.value?.problems ?? [])])
const note = ref(earlier.value?.note ?? '')
const busy = ref(false)
const problem = ref('')

const NEEDS_NOTE: ReportProblem[] = ['OTHER', 'QUESTION']

const needsNote = computed(
  () => problems.value.some((p) => NEEDS_NOTE.includes(p)) && !note.value.trim(),
)
const hintOnly = computed(() => problems.value.length === 1 && problems.value[0] === 'ON_IMAGE')
const canSend = computed(
  () => problems.value.length > 0 && !hintOnly.value && !needsNote.value && !busy.value,
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

const send = () => run(() => save(props.slug, { problems: problems.value, note: note.value }))
const remove = () => run(() => withdraw(props.slug))
</script>

<template>
  <form class="space-y-4" @submit.prevent="canSend && send()">
    <fieldset class="space-y-2">
      <legend class="mb-2 text-body text-muted-foreground">{{ t('account.report.legend') }}</legend>
      <label
        v-for="option in REPORT_PROBLEMS"
        :key="option"
        class="flex items-start gap-2 text-body"
      >
        <input
          v-model="problems"
          :data-testid="`report-${option}`"
          :class="['mt-1', CHECKBOX_CLASS]"
          :value="option"
          type="checkbox"
        />
        <span>{{ t(`account.report.problems.${option}`) }}</span>
      </label>
    </fieldset>

    <label class="block space-y-1 text-body">
      <span class="block">{{
        t(needsNote ? 'account.report.noteRequired' : 'account.report.note')
      }}</span>
      <textarea
        v-model="note"
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
