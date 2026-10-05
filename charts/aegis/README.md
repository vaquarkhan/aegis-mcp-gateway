<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Aegis Helm chart

Runs the gateway image built from the repository [`Dockerfile`](../../Dockerfile) as a StatefulSet, so
each replica keeps its own audit journal on its own volume.

## Quick start

```bash
# 1. Build and push the image from the repository root
docker build -t registry.example.com/aegis-gateway:2.0.0-SNAPSHOT .
docker push registry.example.com/aegis-gateway:2.0.0-SNAPSHOT

# 2. Create the secret names referenced by the configuration (values only live in Kubernetes Secrets)
kubectl create secret generic aegis-secrets \
  --from-literal=AEGIS_SIGNING_KEY="$(openssl rand -hex 32)" \
  --from-literal=CATALOG_TOKEN="replace-me"

# 3. Install with your values file
helm install aegis charts/aegis -f my-values.yaml \
  --set image.repository=registry.example.com/aegis-gateway
```

`my-values.yaml` carries the Aegis configuration under `config:` (see
[`ci/test-values.yaml`](ci/test-values.yaml) for a complete CI example) and names the secrets with
`envFrom`. The configuration holds secret **names** only, never values.

## Example values

### Minimal own-host (one replica, JWT callers, one HTTP tool)

Save as `my-values.yaml` and adjust issuer, JWKS, and backend URL for your cluster:

```yaml
image:
  repository: registry.example.com/aegis-gateway
  tag: 2.0.0-SNAPSHOT

envFrom:
  - secretRef: {name: aegis-secrets}   # must provide AEGIS_SIGNING_KEY and CATALOG_TOKEN

config:
  gateway: {name: aegis}
  access:
    writeGroups: [engineers]
    approverGroups: [approvers]
  approval: {keyEnv: AEGIS_SIGNING_KEY}
  policy:
    rules:
      - {id: read, groups: [analysts, engineers], classes: [READ]}
  egress:
    allowCidrs: [10.0.0.0/8]           # backends inside the cluster CIDR
  audit:
    file: /var/lib/aegis/audit/aegis.jsonl
  sources:
    - id: catalog
      type: http
      baseUrl: http://catalog.data.svc:8181
      credentials: {type: bearer, tokenEnv: CATALOG_TOKEN}
      tools:
        - name: list_namespaces
          description: List catalog namespaces.
          class: READ
          request: {method: GET, path: /v1/namespaces}
  host:
    listen: {host: 0.0.0.0, port: 8080}  # must match containerPort
    identity:
      type: jwt
      jwt:
        issuer: https://idp.example.com/realms/acme
        audience: aegis-gateway
        jwksUri: https://idp.example.com/realms/acme/protocol/openid-connect/certs
```

Install and smoke-check:

```bash
helm upgrade --install aegis charts/aegis -f my-values.yaml
kubectl rollout status statefulset/aegis
kubectl port-forward svc/aegis 8080:8080
curl -sS http://127.0.0.1:8080/readyz
```

### NetworkPolicy + PDB (CI-style)

Same shape as [`ci/test-values.yaml`](ci/test-values.yaml): only labeled MCP client pods may connect,
egress is limited to a CIDR, and a PodDisruptionBudget keeps one pod available.

```yaml
networkPolicy:
  enabled: true
  ingressFrom:
    - podSelector: {matchLabels: {app.kubernetes.io/name: mcp-client}}
  egressTo:
    - ipBlock: {cidr: 10.0.0.0/8}
podDisruptionBudget:
  enabled: true
  minAvailable: 1
```

### Two replicas (shared PostgreSQL state)

Approvals and dry-run receipts must be shared across pods. The chart **refuses** `replicaCount > 1`
unless `sharedState: true` and your `config.state` uses PostgreSQL (`aegis-state-postgres`).

```yaml
replicaCount: 2
sharedState: true

envFrom:
  - secretRef: {name: aegis-secrets}          # AEGIS_SIGNING_KEY
  - secretRef: {name: aegis-postgres}         # e.g. PGHOST, PGUSER, PGPASSWORD, PGDATABASE

config:
  # ... same host / policy / sources as above ...
  audit:
    file: /var/lib/aegis/audit/aegis.jsonl    # still on each pod's own volume
  state:
    type: postgres
    # keys as documented in docs/CONFIGURATION.md / aegis-state-postgres/README.md
```

### TLS material or plugin jars as extra volumes

```yaml
extraVolumes:
  - name: tls
    secret: {secretName: aegis-tls}
extraVolumeMounts:
  - name: tls
    mountPath: /etc/aegis/tls
    readOnly: true
```

### Render and validate without installing

```bash
helm lint --strict charts/aegis -f charts/aegis/ci/test-values.yaml
helm template aegis charts/aegis -f charts/aegis/ci/test-values.yaml > /tmp/aegis-manifests.yaml
# optional: kubeconform -strict -kubernetes-version 1.33.0 /tmp/aegis-manifests.yaml
```

## What the chart checks when it renders

| Rule | Why |
| --- | --- |
| `config` (or `existingConfigMap`) is set | there is no useful default configuration |
| `config.host.listen.host` is `0.0.0.0` and the port equals `containerPort` | a loopback listener inside a pod is unreachable |
| `config.audit.file` is under `persistence.mountPath` | the root filesystem is read-only and the journal must survive restarts |
| `replicaCount > 1` only with `sharedState: true` | approvals and dry-run receipts must be shared (`state` with `type: postgres`) |
| `networkPolicy.enabled: true` requires one or more `networkPolicy.ingressFrom` peers | a port-only Kubernetes ingress rule would allow all sources |

The pod runs as UID 10001 with a read-only root filesystem, no capabilities, no service account
token, and `RuntimeDefault` seccomp. An optional NetworkPolicy adds a network-level limit on top of
the gateway's own egress allow list.

## How it was checked

On 2026-10-03, with Helm 4.3.0, kubeconform 0.8.0 and hadolint 2.15.1 (release binaries, checksums
verified):

- `helm lint --strict -f ci/test-values.yaml`: 0 failures.
- `helm template ... | kubeconform -strict -kubernetes-version 1.33.0`: 7 resources, all valid.
- Each refusal in the table above renders an error, and two replicas render with `sharedState: true`.
- [`ci/start_rendered.py`](ci/start_rendered.py) started the gateway with the rendered `aegis.yaml` and
  the StatefulSet's own java arguments and `/opt/aegis` layout (gateway jar plus the a2a plugin jar);
  `/readyz` answered `{"status":"ready","activeTools":1}`.
- `hadolint Dockerfile`: no findings.

Not checked: the image was not built and the chart was not installed in a cluster, because no
container runtime was available locally (see [Tested environments](../../docs/VALIDATION.md#tested-environments)).
The CI workflow builds the image on a Linux runner.

## Related module docs

- [`aegis-gateway/README.md`](../../aegis-gateway/README.md) — own-host jar and CLI
- [`aegis-state-postgres/README.md`](../../aegis-state-postgres/README.md) — shared state for replicas
- [`aegis-config/README.md`](../../aegis-config/README.md) — how YAML becomes a running gateway
