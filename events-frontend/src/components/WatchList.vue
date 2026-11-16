<script setup lang="ts">
/**
 * The acts the visitor waits for, on the Following page's follows tab (#5214): watched by their MusicBrainz id, with no
 * Berlin date yet. Once one gets a date it becomes an ordinary follow and leaves this list. "Add an act" opens the picker.
 */
import { BellPlus } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import ErrorLine from '@/components/ErrorLine.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import WatchPicker from '@/components/WatchPicker.vue'
import { Button } from '@/components/ui/button'
import { prepareCsrf, unwatchAct, type Watch } from '@/api/account'
import { CARD_LIST_CLASS } from '@/lib/utils'

const props = defineProps<{ watches: Watch[] }>()
/** A watch was added or removed, or a pick followed an artist: the page reloads what it lists. */
const emit = defineEmits<{ change: [] }>()

const { t } = useI18n()

const open = ref(false)
const busy = ref<string | null>(null)
const problem = ref('')
let csrfReady: Promise<void> | null = null

async function remove(watch: Watch) {
  if (busy.value) return
  busy.value = watch.mbid
  problem.value = ''
  try {
    csrfReady ??= prepareCsrf()
    await csrfReady
    await unwatchAct(watch.mbid)
    emit('change')
  } catch {
    problem.value = t('account.watch.failed')
  } finally {
    busy.value = null
  }
}
</script>

<template>
  <section class="space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <!-- No heading over an empty list: a sentence says what the button is for. -->
      <SectionLabel v-if="props.watches.length" as="h2">{{
        t('account.watch.list', { count: props.watches.length })
      }}</SectionLabel>
      <p v-else class="text-body text-muted-foreground">{{ t('account.watch.invite') }}</p>
      <Button v-if="!open" size="sm" variant="outline" @click="open = true">
        <BellPlus data-icon="inline-start" />
        {{ t('account.watch.add') }}
      </Button>
      <Button v-else size="sm" variant="ghost" @click="open = false">
        {{ t('account.watch.close') }}
      </Button>
    </div>
    <WatchPicker v-if="open" no-hint @change="emit('change')" />
    <ErrorLine v-if="problem">{{ problem }}</ErrorLine>
    <ul v-if="props.watches.length" :class="CARD_LIST_CLASS">
      <li
        v-for="watch in props.watches"
        :key="watch.mbid"
        class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3"
      >
        <div class="min-w-0">
          <p class="text-body font-medium">{{ watch.name }}</p>
          <p class="text-meta text-muted-foreground">{{ t('account.watch.kind') }}</p>
        </div>
        <Button
          :aria-label="t('account.watch.removeLabel', { name: watch.name })"
          :disabled="busy !== null"
          size="sm"
          variant="outline"
          @click="remove(watch)"
        >
          {{ t('account.watch.remove') }}
        </Button>
      </li>
    </ul>
  </section>
</template>
