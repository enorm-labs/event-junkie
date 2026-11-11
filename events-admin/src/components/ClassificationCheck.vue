<script setup lang="ts">
import { LoaderCircle, Sparkles } from '@lucide/vue'
import { computed, ref } from 'vue'
import { RouterLink } from 'vue-router'

import { checkEvent, type ClassificationRun } from '@/api/dataQuality'
import { Button } from '@/components/ui/button'
import { errorText } from '@/lib/errorText'

/** The AI check of one event: asked, running, and what the last run reported. Remount it for another event. */
const props = defineProps<{ eventId: number }>()
const emit = defineEmits<{ leave: [] }>()

const confirming = ref(false)
const checking = ref(false)
const run = ref<ClassificationRun | null>(null)
const error = ref<string | null>(null)

/** One line for what the run did. A run of one event reports at most one of these. */
const outcome = computed(() => {
  const current = run.value
  if (!current) return null
  if (current.overBudget > 0) return 'Not sent: the month’s token budget is spent.'
  if (current.unchanged > 0) {
    return 'Skipped: the title, subtitle and description did not change since the last check.'
  }
  if (current.failed > 0) return 'The model gave no answer. The nightly run tries again.'
  if (current.overCap > 0) return 'Not sent: the run reached its cap on model calls.'
  if (current.checked > 0) {
    const fields = Object.keys(current.suggested)
    return fields.length
      ? `Checked. New suggestions for: ${fields.join(', ')}.`
      : 'Checked. No field differs from the stored value.'
  }
  return 'Not sent to the model.'
})

async function runCheck() {
  confirming.value = false
  checking.value = true
  error.value = null
  run.value = null
  try {
    run.value = await checkEvent(props.eventId)
  } catch (e) {
    error.value = errorText(e)
  } finally {
    checking.value = false
  }
}
</script>

<template>
  <section class="flex flex-col gap-2" aria-labelledby="check-heading">
    <h3 id="check-heading" class="text-sm font-medium">Check with AI</h3>
    <p class="text-xs text-muted-foreground">
      Proposes fixes for this event. It changes nothing: a proposal waits on Suggested fixes.
    </p>
    <div
      v-if="confirming"
      class="flex flex-col gap-2 rounded-md border border-primary/40 bg-muted/50 p-3 text-sm"
      data-testid="check-confirm"
      role="group"
      aria-label="Confirm the check"
    >
      <p>One model call, counted against the month’s token budget. Check this event?</p>
      <div class="flex flex-wrap gap-2">
        <Button size="sm" type="button" @click="runCheck">Check</Button>
        <Button size="sm" type="button" variant="outline" @click="confirming = false">
          Cancel
        </Button>
      </div>
    </div>
    <div v-else>
      <Button
        :disabled="checking"
        size="sm"
        type="button"
        variant="outline"
        @click="confirming = true"
      >
        <LoaderCircle v-if="checking" aria-hidden="true" class="animate-spin" />
        <Sparkles v-else aria-hidden="true" />
        Check with AI
      </Button>
    </div>
    <p v-if="error" class="text-sm text-destructive" role="alert">{{ error }}</p>
    <p v-if="outcome" class="text-sm" data-testid="check-outcome" role="status">
      {{ outcome }}
      <RouterLink class="underline underline-offset-4" to="/suggestions" @click="emit('leave')">
        Suggested fixes
      </RouterLink>
    </p>
  </section>
</template>
