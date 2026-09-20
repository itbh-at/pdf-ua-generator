{{/*
SPDX-License-Identifier: Apache-2.0
Copyright 2026 IT Beratung Hermann GmbH
*/}}

{{- define "pdf-ua-generator.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "pdf-ua-generator.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else if hasPrefix (include "pdf-ua-generator.name" .) .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name (include "pdf-ua-generator.name" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "pdf-ua-generator.labels" -}}
app.kubernetes.io/name: {{ include "pdf-ua-generator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version }}
{{- end -}}

{{- define "pdf-ua-generator.selectorLabels" -}}
app.kubernetes.io/name: {{ include "pdf-ua-generator.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{/* The Secret holding the database credentials: an existing one, or ours. */}}
{{- define "pdf-ua-generator.databaseSecret" -}}
{{- default (printf "%s-database" (include "pdf-ua-generator.fullname" .)) .Values.database.existingSecret -}}
{{- end -}}
