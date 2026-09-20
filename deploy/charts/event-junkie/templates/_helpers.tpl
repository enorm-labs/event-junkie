{{/* Shared templates, namespaced under `event-junkie.` per the Helm guide. Per-workload templates take
   a dict rather than the root context — `(dict "ctx" $ "component" "bff")` — where `component` is
   also the key under `.Values` holding that workload's settings. */}}

{{/*
The chart name, overridable. Used for `app.kubernetes.io/name`.
*/}}
{{- define "event-junkie.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* The release-qualified base name, truncated to 55 so the per-component suffix fits inside 63. */}}
{{- define "event-junkie.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 55 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 55 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 55 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/* One workload's objects — `<release>-event-junkie-bff`. Deployment, Service and ServiceAccount share it. */}}
{{- define "event-junkie.componentName" -}}
{{- printf "%s-%s" (include "event-junkie.fullname" .ctx) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* `helm.sh/chart`. `replace "+" "_"`: SemVer build metadata is illegal in a label value. */}}
{{- define "event-junkie.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/* The full label set, for `metadata.labels` only. NEVER for `spec.selector.matchLabels`: the
   selector is immutable, `helm.sh/chart` and `app.kubernetes.io/version` change every release, and a
   chart that mixes them installs and fails the *second* release. `tests/invariants_test.yaml` fails
   the build if either label reaches a selector. */}}
{{- define "event-junkie.labels" -}}
helm.sh/chart: {{ include "event-junkie.chart" .ctx }}
{{ include "event-junkie.selectorLabels" . }}
{{- if .ctx.Chart.AppVersion }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
app.kubernetes.io/part-of: {{ include "event-junkie.name" .ctx }}
{{- end }}

{{/* The immutable subset, for selectors. `component` belongs here: without it all three Deployments
   select each other's pods. The rule is "the labels that cannot change", not "the shortest set". */}}
{{- define "event-junkie.selectorLabels" -}}
app.kubernetes.io/name: {{ include "event-junkie.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/* One workload's ServiceAccount name, resolved the same way whether the chart creates it or not, so
   a hand-made RBAC binding still lines up. */}}
{{- define "event-junkie.serviceAccountName" -}}
{{- $component := index .ctx.Values .component -}}
{{- if $component.serviceAccount.create -}}
{{- default (include "event-junkie.componentName" .) $component.serviceAccount.name -}}
{{- else -}}
{{- default "default" $component.serviceAccount.name -}}
{{- end -}}
{{- end }}

{{/* A fully-qualified image reference. `tag` falls back to `.Chart.AppVersion` (#264 stamps both from
   one build); with a `digest` (#1473) it is `repo:tag@sha256:…`, so a repointed tag changes nothing
   and the tag stays readable. */}}
{{- define "event-junkie.imageRef" -}}
{{- $registry := .image.registry | default .defaultRegistry -}}
{{- $tag := .image.tag | default .appVersion -}}
{{- $ref := printf "%s/%s:%s" $registry .image.repository $tag -}}
{{- if .image.digest -}}
{{- printf "%s@%s" $ref .image.digest -}}
{{- else -}}
{{- $ref -}}
{{- end -}}
{{- end }}

{{- define "event-junkie.image" -}}
{{- $component := index .ctx.Values .component -}}
{{- include "event-junkie.imageRef" (dict "image" $component.image "defaultRegistry" .ctx.Values.image.registry "appVersion" .ctx.Chart.AppVersion) -}}
{{- end }}

{{/* Pod-level security context. `runAsUser` has to match the UID the image runs as; a component may override. */}}
{{- define "event-junkie.podSecurityContext" -}}
{{- $component := index .ctx.Values .component -}}
runAsNonRoot: true
runAsUser: {{ $component.runAsUser | default .ctx.Values.security.runAsUser }}
runAsGroup: {{ $component.runAsGroup | default .ctx.Values.security.runAsGroup }}
fsGroup: {{ $component.runAsGroup | default .ctx.Values.security.runAsGroup }}
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{/* Container-level security context. `readOnlyRootFilesystem` is the one with a cost: every writable
   path needs an explicit emptyDir mount. */}}
{{- define "event-junkie.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end }}

{{/* The R2DBC connection, for both JVM services, from one place. `database.existingSecret` is
   required; no inline-credential path exists in this chart. */}}
{{- define "event-junkie.databaseEnv" -}}
{{- $secret := required "database.existingSecret is required — create the Secret out of band (see values.yaml) and name it here. This chart never templates a password." .Values.database.existingSecret -}}
{{- $host := required "database.host is required — it is `postgres_ip` from the matching infra/environments/<env> stack." .Values.database.host -}}
- name: SPRING_R2DBC_URL
  value: {{ printf "r2dbc:postgresql://%s:%v/%s" $host .Values.database.port .Values.database.name | quote }}
- name: SPRING_R2DBC_USERNAME
  valueFrom:
    secretKeyRef:
      name: {{ $secret | quote }}
      key: {{ .Values.database.secretKeys.username | quote }}
- name: SPRING_R2DBC_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ $secret | quote }}
      key: {{ .Values.database.secretKeys.password | quote }}
{{- end }}

{{/* The Flyway connection — importer only (ADR-005), JDBC because Flyway has no reactive driver. Two
   connection styles for one database, and the failure is a migration that never runs. Note
   `SPRING_FLYWAY_USER`, not `_USERNAME`, which binds to nothing. */}}
{{- define "event-junkie.flywayEnv" -}}
- name: SPRING_FLYWAY_URL
  value: {{ printf "jdbc:postgresql://%s:%v/%s" .Values.database.host .Values.database.port .Values.database.name | quote }}
- name: SPRING_FLYWAY_USER
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret | quote }}
      key: {{ .Values.database.secretKeys.username | quote }}
- name: SPRING_FLYWAY_PASSWORD
  valueFrom:
    secretKeyRef:
      name: {{ .Values.database.existingSecret | quote }}
      key: {{ .Values.database.secretKeys.password | quote }}
{{- end }}

{{/* Three probes over two actuator paths on the management port. **What the two paths mean is
   decided in each service's `application.yaml`** (ADR-018): the BFF's readiness group includes the
   database and the schema, the importer's does not. The same template renders probes with different
   semantics per component. */}}
{{- define "event-junkie.jvmProbes" -}}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 5
  {{- /* 30 × 5s = 150s for a cold JVM, and *only* for one. This watches the liveness path, which must
     never wait for the importer's migrations — that is readiness' job (ADR-018). */}}
  failureThreshold: 30
livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 15
  failureThreshold: 3
readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: management
  {{- /* 3 × 10s = 30s of *sustained* failure before a pod leaves the Service. With the database in the
     BFF's readiness group (#438) this is the blip tolerance, load-bearing, not a default. */}}
  periodSeconds: 10
  failureThreshold: 3
{{- end }}
