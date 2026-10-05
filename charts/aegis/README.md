<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Aegis Helm chart

Runs the gateway image built from the repository [`Dockerfile`](../../Dockerfile) as a StatefulSet, so
each replica keeps its own audit journal on its own volume.

```bash
docker build -t registry.example.com/aegis-gateway:2.0.0-SNAPSHOT .
docker push registry.example.com/aegis-gateway:2.0.0-SNAPSHOT
kubectl create secret generic aegis-secrets --from-literal=AEGIS_SIGNING_KEY="$(openssl rand -hex 32)"
helm install aegis charts/aegis -f my-values.yaml --set image.repository=registry.example.com/aegis-gateway
```

`my-values.yaml` carries the Aegis configuration under `config:` (see
[`ci/test-values.yaml`](ci/test-values.yaml) for a complete example) and names the secrets with
`envFrom`. The configuration holds secret names only, never values.

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
