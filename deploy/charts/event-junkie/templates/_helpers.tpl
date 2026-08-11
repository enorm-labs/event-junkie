{{/*
Shared templates. Everything here is namespaced under `event-junkie.` per the Helm guide — a bare
`fullname` would collide the moment this chart is ever used as a subchart.

Templates that vary per workload take a dict rather than the root context:

    {{ include "event-junkie.labels" (dict "ctx" $ "component" "bff") }}

`ctx` is the root context, `component` is one of bff / importer / frontend and is also the key under
`.Values` holding that workload's settings.
*/}}

{{/*
The chart name, overridable. Used for `app.kubernetes.io/name`.
*/}}
{{- define "event-junkie.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
The release-qualified base name. Truncated to 63 because some Kubernetes name fields are limited to
that; the per-component suffix is added on top, so this leaves room by truncating to 55.
*/}}
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

{{/*
The name of one workload's objects — `<release>-event-junkie-bff` and so on. Deployment, Service and
ServiceAccount all share it.
*/}}
{{- define "event-junkie.componentName" -}}
{{- printf "%s-%s" (include "event-junkie.fullname" .ctx) .component | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
The `helm.sh/chart` label. `replace "+" "_"` because SemVer build metadata is legal in a chart
version and illegal in a Kubernetes label value.
*/}}
{{- define "event-junkie.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
The full label set, for `metadata.labels` only.

NEVER use this for a Deployment's `spec.selector.matchLabels`. `spec.selector` is immutable after
creation, and both `helm.sh/chart` and `app.kubernetes.io/version` change on every release — a chart
that mixes them installs perfectly and then fails every subsequent upgrade with an immutable-field
error, which is a failure nobody sees until the *second* release. `scripts/render-assertions.sh`
fails the build if either label reaches a selector.
*/}}
{{- define "event-junkie.labels" -}}
helm.sh/chart: {{ include "event-junkie.chart" .ctx }}
{{ include "event-junkie.selectorLabels" . }}
{{- if .ctx.Chart.AppVersion }}
app.kubernetes.io/version: {{ .ctx.Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .ctx.Release.Service }}
app.kubernetes.io/part-of: {{ include "event-junkie.name" .ctx }}
{{- end }}

{{/*
The immutable subset, for `spec.selector.matchLabels` and for a Service's `spec.selector`.

`component` belongs here even though the guide's example selector is name+instance only: without it
all three Deployments would select each other's pods, and their Services would round-robin across
the whole release. It is safe because a workload's component never changes — that is the actual
rule, "the labels that cannot change", not "the shortest possible set".
*/}}
{{- define "event-junkie.selectorLabels" -}}
app.kubernetes.io/name: {{ include "event-junkie.name" .ctx }}
app.kubernetes.io/instance: {{ .ctx.Release.Name }}
app.kubernetes.io/component: {{ .component }}
{{- end }}

{{/*
One workload's ServiceAccount name, resolved the same way whether the chart creates it or an
operator did — so a hand-made RBAC binding still lines up.
*/}}
{{- define "event-junkie.serviceAccountName" -}}
{{- $component := index .ctx.Values .component -}}
{{- if $component.serviceAccount.create -}}
{{- default (include "event-junkie.componentName" .) $component.serviceAccount.name -}}
{{- else -}}
{{- default "default" $component.serviceAccount.name -}}
{{- end -}}
{{- end }}

{{/*
A fully-qualified image reference. `tag` falls back to `.Chart.AppVersion` so the chart version and
the image tag move together (#264 stamps both from one build).
*/}}
{{- define "event-junkie.image" -}}
{{- $component := index .ctx.Values .component -}}
{{- $registry := $component.image.registry | default .ctx.Values.image.registry -}}
{{- $tag := $component.image.tag | default .ctx.Chart.AppVersion -}}
{{- printf "%s/%s:%s" $registry $component.image.repository $tag -}}
{{- end }}

{{/*
Pod-level security context. `runAsUser` has to match the UID the image actually runs as (#426), so a
component may override the chart-wide default.
*/}}
{{- define "event-junkie.podSecurityContext" -}}
{{- $component := index .ctx.Values .component -}}
runAsNonRoot: true
runAsUser: {{ $component.runAsUser | default .ctx.Values.security.runAsUser }}
runAsGroup: {{ $component.runAsGroup | default .ctx.Values.security.runAsGroup }}
fsGroup: {{ $component.runAsGroup | default .ctx.Values.security.runAsGroup }}
seccompProfile:
  type: RuntimeDefault
{{- end }}

{{/*
Container-level security context. `readOnlyRootFilesystem` is the one with a cost: every writable
path a process needs must then be an explicit emptyDir mount, which is the part that gets missed.
*/}}
{{- define "event-junkie.containerSecurityContext" -}}
allowPrivilegeEscalation: false
readOnlyRootFilesystem: true
capabilities:
  drop:
    - ALL
{{- end }}

{{/*
The R2DBC connection, for both JVM services. Rendered from one place so neither can get half of it.
`database.existingSecret` is required and the render fails without it: no inline-credential path
exists anywhere in this chart.
*/}}
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

{{/*
The Flyway connection — importer only, per ADR-005, and JDBC rather than R2DBC because Flyway has no
reactive driver. Two connection styles for one database is the detail that gets forgotten, and its
failure mode is a migration that never runs rather than a startup error.

Note `SPRING_FLYWAY_USER`, not `_USERNAME`. Spring's Flyway property is `spring.flyway.user`; the
`_USERNAME` spelling binds to nothing and fails silently.
*/}}
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

{{/*
The two actuator probes, on the management port. Spring Boot enables the probe health groups on its
own when it detects Kubernetes; the ConfigMap sets the property explicitly rather than relying on
that autodetection.
*/}}
{{- define "event-junkie.jvmProbes" -}}
startupProbe:
  httpGet:
    path: /actuator/health/liveness
    port: management
  periodSeconds: 5
  {{- /* 30 × 5s = 150s for a cold JVM plus, on a first install, the wait for the importer's
         migrations to create the schema. See the chart README. */}}
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
  periodSeconds: 10
  failureThreshold: 3
{{- end }}
