<script setup lang="ts">
/**
 * The problem reports allowed accounts file on event, act, promoter and venue pages (#4399,
 * #5603). One row per subject. Resolve deletes its reports, as the privacy notice promises, so resolve once the fix is live or the
 * verdict is final. `/fix-events` works the same queue.
 */
import { onMounted, ref } from 'vue'

import {
  fetchReports,
  KIND_LABELS,
  PROBLEM_LABELS,
  type ReportedSubject,
  resolveReports,
} from '@/api/reports'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { useLoad } from '@/composables/useLoad'
import { publicOrigin } from '@/lib/environments'
import { errorText } from '@/lib/errorText'

const origin = publicOrigin(import.meta.env.MODE)

const { data: subjects, loading, error, reload: load } = useLoad(fetchReports, [])
const busy = ref(false)

async function resolve(subject: ReportedSubject) {
  busy.value = true
  error.value = null
  try {
    await resolveReports(subject)
    await load()
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

onMounted(load)
</script>

<template>
  <p class="mb-4 text-muted-foreground">
    Problems reported on event, act, promoter and venue pages. Resolve deletes a page's reports: do
    it once the fix is live or the verdict is final.
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
                @click="resolve(subject)"
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
</template>
