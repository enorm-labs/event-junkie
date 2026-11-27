<script setup lang="ts">
/**
 * The problem reports accounts file on event, act, promoter and venue pages (#4399, #5603). One
 * row per subject. Resolve asks for the outcome and an optional reply, deletes the page's reports,
 * as the privacy notice promises, and leaves each reporter a note under the bell (#5308). Resolve
 * once the fix is live or the verdict is final. `/fix-events` works the same queue.
 */
import { onMounted, ref } from 'vue'

import {
  fetchReporters,
  fetchReports,
  KIND_LABELS,
  MAX_REPLY,
  PROBLEM_LABELS,
  type ReportedSubject,
  type ReportOutcome,
  resolveReports,
} from '@/api/reports'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetFooter,
  SheetHeader,
  SheetTitle,
} from '@/components/ui/sheet'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { Textarea } from '@/components/ui/textarea'
import { useLoad } from '@/composables/useLoad'
import { publicOrigin } from '@/lib/environments'
import { errorText } from '@/lib/errorText'

const origin = publicOrigin(import.meta.env.MODE)

const { data: subjects, loading, error, reload: load } = useLoad(fetchReports, [])
const { data: reporters, reload: loadReporters } = useLoad(fetchReporters, [])
/** The accounts named on the line above the table; the rest are summed. */
const SHOWN = 3
const busy = ref(false)
/** The subject the resolve sheet is open for, and what it says to the reporters. */
const resolving = ref<ReportedSubject | null>(null)
const outcome = ref<ReportOutcome>('fixed')
const reply = ref('')

function ask(subject: ReportedSubject) {
  resolving.value = subject
  outcome.value = 'fixed'
  reply.value = ''
}

async function resolve() {
  const subject = resolving.value
  if (!subject) return
  busy.value = true
  error.value = null
  try {
    await resolveReports(subject, outcome.value, reply.value)
    resolving.value = null
    await Promise.all([load(), loadReporters()])
  } catch (e) {
    error.value = errorText(e)
  } finally {
    busy.value = false
  }
}

const problems = (subject: ReportedSubject) => [
  ...new Set(subject.reports.flatMap((report) => report.problems)),
]
const notes = (subject: ReportedSubject) =>
  subject.reports.map((report) => report.note).filter((note): note is string => Boolean(note))
const source = (subject: ReportedSubject) =>
  subject.sourceSlug ?? (subject.kind === 'events' ? 'manual' : '—')

onMounted(() => Promise.all([load(), loadReporters()]))
</script>

<template>
  <p class="mb-4 text-muted-foreground">
    Problems reported on event, act, promoter and venue pages. Resolve deletes a page's reports and
    tells each reporter the outcome under the bell: do it once the fix is live or the verdict is
    final.
  </p>

  <p v-if="reporters.length" class="mb-4" data-testid="reporters">
    Open reports per account:
    <template v-for="(reporter, index) in reporters.slice(0, SHOWN)" :key="reporter.email"
      ><template v-if="index">, </template>{{ reporter.email }} ({{
        reporter.openReports
      }})</template
    ><template v-if="reporters.length > SHOWN">, and {{ reporters.length - SHOWN }} more</template>
  </p>

  <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
  <div class="overflow-hidden rounded-lg border">
    <Table>
      <TableHeader class="bg-muted">
        <TableRow>
          <TableHead>Date or kind</TableHead>
          <TableHead>Page</TableHead>
          <TableHead>Source</TableHead>
          <TableHead>Problems</TableHead>
          <TableHead>Notes</TableHead>
          <TableHead><span class="sr-only">Actions</span></TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <TableRow v-if="loading">
          <TableCell class="h-24 text-center text-muted-foreground" colspan="6">
            Loading the reports…
          </TableCell>
        </TableRow>
        <template v-else>
          <TableRow v-for="subject in subjects" :key="`${subject.kind}/${subject.id}`">
            <TableCell class="tabular-nums">
              <template v-if="subject.eventDate">{{ subject.eventDate }}</template>
              <Badge v-else variant="secondary">{{ KIND_LABELS[subject.kind] }}</Badge>
            </TableCell>
            <TableCell class="whitespace-normal">
              <a
                :href="`${origin}/de/${subject.kind}/${subject.slug}`"
                class="underline-offset-4 hover:underline"
                rel="noopener noreferrer"
                target="_blank"
              >
                {{ subject.name }}
              </a>
              <span v-if="subject.venue" class="block text-muted-foreground">{{
                subject.venue
              }}</span>
            </TableCell>
            <TableCell class="font-mono">{{ source(subject) }}</TableCell>
            <TableCell class="whitespace-normal">
              <span class="flex flex-wrap gap-1">
                <Badge v-for="problem in problems(subject)" :key="problem" variant="outline">
                  {{ PROBLEM_LABELS[problem] ?? problem }}
                </Badge>
              </span>
            </TableCell>
            <TableCell class="whitespace-normal">
              <p v-for="(note, index) in notes(subject)" :key="index">{{ note }}</p>
            </TableCell>
            <TableCell class="text-right">
              <Button
                :aria-label="`Resolve: ${subject.name}`"
                :disabled="busy"
                size="sm"
                variant="outline"
                @click="ask(subject)"
              >
                Resolve
              </Button>
            </TableCell>
          </TableRow>
          <TableRow v-if="!subjects.length">
            <TableCell class="h-24 text-center" colspan="6">No open reports.</TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>
  </div>

  <Sheet :open="resolving !== null" @update:open="(value) => value || (resolving = null)">
    <SheetContent class="w-full overflow-y-auto sm:max-w-lg">
      <SheetHeader>
        <SheetTitle>Resolve: {{ resolving?.name }}</SheetTitle>
        <SheetDescription>
          Each of the {{ resolving?.reports.length }} reporters gets a note under the bell with the
          page's name, the outcome and the reply.
        </SheetDescription>
      </SheetHeader>
      <form id="resolve-form" class="space-y-4 px-4" @submit.prevent="resolve">
        <fieldset class="space-y-2">
          <legend class="mb-2 font-medium">Outcome</legend>
          <label class="flex items-center gap-2" for="outcome-fixed">
            <input id="outcome-fixed" v-model="outcome" name="outcome" type="radio" value="fixed" />
            Fixed
          </label>
          <label class="flex items-center gap-2" for="outcome-unchanged">
            <input
              id="outcome-unchanged"
              v-model="outcome"
              name="outcome"
              type="radio"
              value="unchanged"
            />
            Checked, left as is
          </label>
        </fieldset>
        <label class="flex flex-col gap-2" for="resolve-reply">
          <span class="font-medium">Reply (optional)</span>
          <Textarea
            id="resolve-reply"
            v-model="reply"
            :maxlength="MAX_REPLY"
            aria-describedby="resolve-reply-hint"
            data-testid="resolve-reply"
          />
        </label>
        <p id="resolve-reply-hint" class="text-muted-foreground">
          Every reporter on this page reads it. {{ reply.length }} / {{ MAX_REPLY }}
        </p>
        <p v-if="error" class="text-destructive" role="alert">{{ error }}</p>
      </form>
      <SheetFooter>
        <Button :disabled="busy" form="resolve-form" type="submit">Resolve</Button>
      </SheetFooter>
    </SheetContent>
  </Sheet>
</template>
