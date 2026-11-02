<script setup lang="ts">
/**
 * "Report a problem" on the event page (#4399), only for the accounts the operator allows. It
 * stores a report and nothing more; the operator works the queue. Everyone else keeps the mail
 * line at the end of the page.
 */
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Flag, X } from '@lucide/vue'
import { Button } from '@/components/ui/button'
import { Sheet, SheetContent, SheetTitle } from '@/components/ui/sheet'
import ReportProblemForm from '@/components/ReportProblemForm.vue'
import { useReports } from '@/composables/useReports'
import { useSession } from '@/composables/useSession'

const props = defineProps<{ slug: string; title: string }>()

const { t } = useI18n()
const { account } = useSession()
const { reports } = useReports()

const open = ref(false)
const thanks = ref(false)
const reported = computed(() => Boolean(reports.value[props.slug]))

function done() {
  open.value = false
  thanks.value = reported.value
}
</script>

<template>
  <div v-if="account?.canReport" class="flex flex-wrap items-center gap-3">
    <Button data-testid="report-open" variant="outline" @click="open = true">
      <Flag aria-hidden="true" />
      {{ t(reported ? 'account.report.edit' : 'account.report.open') }}
    </Button>
    <span class="text-body text-muted-foreground empty:hidden" role="status">{{
      thanks ? t('account.report.thanks') : ''
    }}</span>

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
          <ReportProblemForm :slug="slug" @done="done" />
        </div>
      </SheetContent>
    </Sheet>
  </div>
</template>
