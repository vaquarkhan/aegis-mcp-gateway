{{/*
Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
Licensed under the Apache License, Version 2.0
https://www.apache.org/licenses/LICENSE-2.0
*/}}

{{- define "aegis.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "aegis.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "aegis.selectorLabels" -}}
app.kubernetes.io/name: {{ include "aegis.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "aegis.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" }}
{{ include "aegis.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end -}}

{{- define "aegis.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "aegis.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "aegis.image" -}}
{{- if .Values.image.digest -}}
{{- printf "%s@%s" .Values.image.repository .Values.image.digest -}}
{{- else -}}
{{- printf "%s:%s" .Values.image.repository (default .Chart.AppVersion .Values.image.tag) -}}
{{- end -}}
{{- end -}}

{{/* Checks that keep a rendered release from failing at start or losing its journal. */}}
{{- define "aegis.validate" -}}
{{- if and (gt (int .Values.replicaCount) 1) (not .Values.sharedState) -}}
{{- fail "replicaCount > 1 needs shared approval state: configure config.state with type postgres, then set sharedState: true" -}}
{{- end -}}
{{- if and .Values.networkPolicy.enabled (empty .Values.networkPolicy.ingressFrom) -}}
{{- fail "networkPolicy.ingressFrom must list at least one allowed peer when networkPolicy.enabled is true" -}}
{{- end -}}
{{- $complete := false -}}
{{- if and (not .Values.existingConfigMap) (not (empty .Values.config)) -}}
{{- $cc := default dict .Values.config.completeControl -}}
{{- $complete = default false $cc.enabled -}}
{{- end -}}
{{- if $complete -}}
{{- if not (regexMatch "^sha256:[0-9a-f]{64}$" (toString .Values.image.digest)) -}}
{{- fail "complete control requires image.digest as sha256:<64 lower-case hex>; tags are not immutable admission evidence" -}}
{{- end -}}
{{- if not .Values.networkPolicy.enabled -}}
{{- fail "complete control requires networkPolicy.enabled: true" -}}
{{- end -}}
{{- if empty .Values.runtimeClassName -}}
{{- fail "complete control requires runtimeClassName for a sandbox runtime such as gvisor" -}}
{{- end -}}
{{- if not .Values.podSecurityContext.runAsNonRoot -}}
{{- fail "complete control requires podSecurityContext.runAsNonRoot: true" -}}
{{- end -}}
{{- if not .Values.securityContext.readOnlyRootFilesystem -}}
{{- fail "complete control requires securityContext.readOnlyRootFilesystem: true" -}}
{{- end -}}
{{- if .Values.securityContext.allowPrivilegeEscalation -}}
{{- fail "complete control requires securityContext.allowPrivilegeEscalation: false" -}}
{{- end -}}
{{- $seccomp := default dict .Values.podSecurityContext.seccompProfile -}}
{{- if or (empty $seccomp.type) (eq (toString $seccomp.type) "Unconfined") -}}
{{- fail "complete control requires a non-Unconfined podSecurityContext.seccompProfile" -}}
{{- end -}}
{{- end -}}
{{- if not .Values.existingConfigMap -}}
{{- if empty .Values.config -}}
{{- fail "config is required: the Aegis configuration (or set existingConfigMap)" -}}
{{- end -}}
{{- $host := default dict .Values.config.host -}}
{{- $listen := default dict $host.listen -}}
{{- if ne (toString (default "" $listen.host)) "0.0.0.0" -}}
{{- fail "config.host.listen.host must be 0.0.0.0 inside the pod" -}}
{{- end -}}
{{- if ne (int (default 8080 $listen.port)) (int .Values.containerPort) -}}
{{- fail "config.host.listen.port must equal containerPort" -}}
{{- end -}}
{{- $audit := default dict .Values.config.audit -}}
{{- if not (hasPrefix (printf "%s/" .Values.persistence.mountPath) (toString (default "" $audit.file))) -}}
{{- fail (printf "config.audit.file must be under %s, the persistent volume" .Values.persistence.mountPath) -}}
{{- end -}}
{{- end -}}
{{- end -}}
