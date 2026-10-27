<script setup lang="ts">
/**
 * The account's passkeys (#3888): add one on this device, rename it, delete it. The e-mail link stays the
 * way in, so deleting the last one locks nobody out. Hidden in a browser without WebAuthn.
 */
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import BaseInput from '@/components/BaseInput.vue'
import SectionLabel from '@/components/SectionLabel.vue'
import { Button } from '@/components/ui/button'
import {
  addPasskey,
  deletePasskey,
  fetchPasskeys,
  renamePasskey,
  type Passkey,
} from '@/api/account'
import { ApiError } from '@/api/client'
import { berlinDateOf, formatLongDate } from '@/lib/format'
import { cancelled, passkeysSupported } from '@/lib/webauthn'

const { t, locale } = useI18n()

const supported = passkeysSupported()
const passkeys = ref<Passkey[]>([])
const editing = ref<string | null>(null)
const draft = ref('')
const busy = ref(false)
const problem = ref('')

/** A first name the visitor can change: the kind of device, from the user agent. */
function deviceName(): string {
  const agent = navigator.userAgent
  const device = [
    ['iPhone', 'iPhone'],
    ['iPad', 'iPad'],
    ['Android', 'Android'],
    ['Mac OS X', 'Mac'],
    ['Windows', 'Windows'],
    ['Linux', 'Linux'],
  ].find(([needle]) => agent.includes(needle!))
  return device ? device[1]! : t('account.passkeys.defaultName')
}

async function load() {
  try {
    passkeys.value = await fetchPasskeys()
  } catch {
    problem.value = t('account.passkeys.failed')
  }
}

async function add() {
  if (busy.value) return
  busy.value = true
  problem.value = ''
  try {
    await addPasskey(deviceName())
    await load()
  } catch (e) {
    if (cancelled(e)) return
    problem.value =
      e instanceof ApiError && e.status === 409
        ? t('account.passkeys.limit')
        : t('account.passkeys.failed')
  } finally {
    busy.value = false
  }
}

function startRename(passkey: Passkey) {
  editing.value = passkey.id
  draft.value = passkey.name
}

async function rename(passkey: Passkey) {
  const name = draft.value.trim()
  if (!name) return
  problem.value = ''
  try {
    await renamePasskey(passkey.id, name)
    passkey.name = name
    editing.value = null
  } catch {
    problem.value = t('account.passkeys.failed')
  }
}

async function remove(passkey: Passkey) {
  problem.value = ''
  try {
    await deletePasskey(passkey.id)
    passkeys.value = passkeys.value.filter((p) => p.id !== passkey.id)
  } catch {
    problem.value = t('account.passkeys.failed')
  }
}

function date(value: string): string {
  return formatLongDate(berlinDateOf(value), locale.value)
}

onMounted(() => {
  if (supported) void load()
})
</script>

<template>
  <section v-if="supported" class="space-y-3">
    <SectionLabel as="h2">{{ t('account.passkeys.title') }}</SectionLabel>
    <p class="text-body text-muted-foreground">{{ t('account.passkeys.intro') }}</p>
    <p v-if="problem" class="text-body text-destructive" role="alert">{{ problem }}</p>
    <ul v-if="passkeys.length" class="divide-y divide-border border-y border-border">
      <li
        v-for="passkey in passkeys"
        :key="passkey.id"
        class="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3"
      >
        <form
          v-if="editing === passkey.id"
          class="flex min-w-0 flex-1 flex-wrap items-center gap-2"
          @submit.prevent="rename(passkey)"
        >
          <BaseInput
            v-model="draft"
            :aria-label="t('account.passkeys.renameLabel', { name: passkey.name })"
            class="min-w-0 flex-1"
            maxlength="60"
            required
          />
          <Button size="sm" type="submit">{{ t('account.passkeys.save') }}</Button>
          <Button size="sm" type="button" variant="ghost" @click="editing = null">
            {{ t('account.passkeys.cancel') }}
          </Button>
        </form>
        <div v-else class="min-w-0">
          <p class="text-body font-medium">{{ passkey.name }}</p>
          <p class="text-meta text-muted-foreground">
            {{ t('account.passkeys.added', { date: date(passkey.createdAt) }) }}
            <template v-if="passkey.lastUsedAt">
              · {{ t('account.passkeys.used', { date: date(passkey.lastUsedAt) }) }}
            </template>
          </p>
        </div>
        <div v-if="editing !== passkey.id" class="flex gap-2">
          <Button size="sm" variant="ghost" @click="startRename(passkey)">
            {{ t('account.passkeys.rename') }}
          </Button>
          <Button size="sm" variant="outline" @click="remove(passkey)">
            {{ t('account.passkeys.delete') }}
          </Button>
        </div>
      </li>
    </ul>
    <Button :disabled="busy" variant="outline" @click="add">
      {{ busy ? t('account.passkeys.adding') : t('account.passkeys.add') }}
    </Button>
  </section>
</template>
