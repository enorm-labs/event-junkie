<script setup lang="ts">
import { reactive, ref } from 'vue'

import { type Festival, type FestivalRequest, saveFestival } from '@/api/festivals'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'

/** The festival to edit, or null for a new one. */
const props = defineProps<{ festival: Festival | null }>()
const emit = defineEmits<{ saved: [festival: Festival]; cancel: [] }>()

const draft = reactive<FestivalRequest>({
  name: props.festival?.name ?? '',
  url: props.festival?.url ?? '',
  firstDay: props.festival?.firstDay ?? '',
  lastDay: props.festival?.lastDay ?? '',
  noticeDe: props.festival?.noticeDe ?? '',
  noticeEn: props.festival?.noticeEn ?? '',
})
const saving = ref(false)
const error = ref<string | null>(null)

/** The importer's own rules (`FestivalRequest.kt`), checked before the request. */
function invalid(): string | null {
  if (!draft.name.trim()) return 'The name must not be blank.'
  if (!/^https?:\/\/\S+$/.test(draft.url.trim())) return 'The URL must start with https://.'
  if (!draft.firstDay || !draft.lastDay) return 'Set the first and the last day.'
  if (draft.lastDay < draft.firstDay) return 'The last day must not be before the first day.'
  if (!draft.noticeDe.trim() || !draft.noticeEn.trim()) return 'Write the notice in both languages.'
  return null
}

async function submit() {
  error.value = invalid()
  if (error.value) return
  saving.value = true
  try {
    emit('saved', await saveFestival(props.festival?.id ?? null, { ...draft }))
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <form class="flex flex-col gap-4" data-testid="festival-form" novalidate @submit.prevent="submit">
    <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-name">
      Name
      <Input id="festival-name" v-model="draft.name" :disabled="saving" maxlength="255" />
    </label>
    <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-url">
      Programme URL
      <Input
        id="festival-url"
        v-model="draft.url"
        :disabled="saving"
        class="font-mono text-xs"
        type="url"
      />
    </label>
    <div class="flex flex-wrap gap-4">
      <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-first-day">
        First day
        <Input id="festival-first-day" v-model="draft.firstDay" :disabled="saving" type="date" />
      </label>
      <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-last-day">
        Last day
        <Input id="festival-last-day" v-model="draft.lastDay" :disabled="saving" type="date" />
      </label>
    </div>
    <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-notice-de">
      Notice, German
      <Textarea
        id="festival-notice-de"
        v-model="draft.noticeDe"
        :disabled="saving"
        maxlength="500"
        rows="3"
      />
    </label>
    <label class="flex flex-col gap-1.5 text-sm font-medium" for="festival-notice-en">
      Notice, English
      <Textarea
        id="festival-notice-en"
        v-model="draft.noticeEn"
        :disabled="saving"
        maxlength="500"
        rows="3"
      />
    </label>
    <p class="text-xs text-muted-foreground">
      The home page shows the festival from 14 days before its first day to its last day. One or two
      sentences per language.
    </p>
    <p v-if="error" class="text-sm text-destructive" role="alert">{{ error }}</p>
    <div class="flex gap-2">
      <Button :disabled="saving" type="submit">{{ festival ? 'Save' : 'Create' }}</Button>
      <Button :disabled="saving" type="button" variant="outline" @click="emit('cancel')">
        Cancel
      </Button>
    </div>
  </form>
</template>
