<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors

  Licensed under the Apache License, Version 2.0 (the "License");
  you may not use this file except in compliance with the License.
  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
-->

# Declarative Configuration Composition

Aegis supports deterministic, build-time composition of **gateway-level governance** (`gateway.yaml`) and **adopter-specific single-source integrations** (`adopter.yaml`) into a single effective gateway configuration file.

This separates platform security and runtime concerns from individual data-source onboarding while ensuring the deployed configuration remains a single validated YAML artifact.

---

## Two-Layer Model

    gateway.yaml (Platform / Governance)  ──┐
                                            ├──► aegis-gateway --compose ──► effective <adopter>.yaml
    adopter.yaml (Single Source / Hooks)  ──┘

| Layer | Maintained By | Allowed Top-Level Keys |
| :--- | :--- | :--- |
| **`gateway.yaml`** | Platform / Security Team | `host`, `knox`, `completeControl`, `secrets`, `gateway`, `access`, `approval`, `vrp`, `serverCatalog`, `egress`, `policy`, `scopes`, `rateLimits`, `breaker`, `screening`, `output`, `audit`, `catalog`, `server`, `metrics`, `tracing`, `state` (must not define `sources`) |
| **`adopter.yaml`** | Data / Adapter Team | Flat single-source fields normalized into `sources[]` (`id`, `type`, `namePrefix`, `timeoutMs`, `unlistedTools`, `strictProperties`, `defaultClass`, `trust`, `credentials`, `artifacts`, `deploymentAttestation`, `transport`, `era`, `legacyVersion`, `maxResponseBytes`, `url`, `command`, `envFrom`, `env`, `workingDir`, `sandbox`, `resources`, `prompts`, `completions`, `baseUrl`, `settings`, `tools`) plus source-bound `hooks` |
| **Composed Output** | Build / CI Artifact | Normalized, deterministically ordered YAML with the Aegis Apache 2.0 header and `effectiveConfigHash` |

---

## CLI Reference

Configuration composition is invoked through the `aegis-gateway` uber-jar:

    java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
      --compose \
      --gateway-config <path-to-gateway.yaml> \
      --adopter <path-to-adopter.yaml> \
      --out <path-to-output.yaml> \
      [--check]

### Flags

| Flag | Required | Description |
| :--- | :---: | :--- |
| `--compose` | Yes | Runs build-time configuration composition instead of starting the gateway runtime. |
| `--gateway-config <path>` | Yes | Path to the platform governance YAML file. |
| `--adopter <path>` | Yes | Path to an adopter source YAML file. Repeat `--adopter` to compose multiple adopters into one gateway. |
| `--out <path>` | Yes | Target path for the composed YAML file. Must not resolve to any input file. |
| `--check` | No | Validates the composed configuration against the runtime schema and verifies that `--out` matches the deterministic composition output without writing to disk; exits non-zero on validation error or drift. |

---

## Composition Rules & Guarantees

1. **Strict Cross-Adopter Field Ownership (Flat Single-Source Model)**
   Each `adopter.yaml` file represents **one flat source** (`id`, `type`, transport/provider settings, `tools`) plus optional source-bound `hooks`. Top-level `sources`, `policy`, `scopes`, `access`, `approval`, `egress`, `rateLimits`, `audit`, `secrets`, `state`, and all other gateway-owned sections or unknown fields are rejected immediately in adopter files.
2. **Explicit Source-Bound Hook Scoping**
   In Aegis runtime configurations, a top-level hook with an omitted or empty `sources` list executes globally across every configured source. To preserve cross-adopter isolation when composing multiple adopters into one gateway, `ConfigComposer` explicitly binds every adopter-defined hook to its owning adopter (`sources: [<id>]`):
   - If an adopter hook omits `sources`, `ConfigComposer` emits `sources: [<id>]` automatically.
   - If an adopter hook explicitly sets `sources: [<id>]`, it is preserved.
   - If an adopter hook declares `sources: []` (attempting global scope) or names any other source ID, composition fails closed with a `ConfigException`.
   - Global hooks that span multiple sources must be declared in `gateway.yaml`.
3. **Cross-Adopter Collision Detection**
   When multiple `--adopter` files are supplied, duplicate source IDs, exposed tool names (`namePrefix + toolName`), or hook identifiers across files are rejected.
4. **Schema-Aware Path Resolution**
   `ConfigComposer` resolves paths only at explicit schema filesystem locations (such as `audit.file`, `approval.keyFile`, `serverCatalog.*File`, `catalog.pinFile`, `secrets.settings` for `file`/`vault`, `state.settings` for `derby`/`postgres`, and per-source `workingDir`, `artifacts[].path`, and `command[]` when `workingDir` is unset):
   - Authoring-relative paths (`./...` or `../...`) are resolved against the declaring file's directory and rebased relative to `--out`'s parent directory.
   - Runtime-relative paths without `./` or `../` (such as `audit/aegis-hive.jsonl`), absolute paths, and URIs (`http://`, `https://`, `jdbc:...`) are preserved verbatim.
   - When `workingDir` is set on a stdio source, `workingDir` is rebased and relative `command[]` entries (such as `./run.sh`) are preserved verbatim because the runtime resolves them relative to `workingDir`.
   - Arbitrary provider `settings` maps and HTTP `tools.*.request` payloads/queries are never rewritten.
5. **Runtime Schema Validation**
   Before writing `--out` or reporting `--check` success, `ConfigComposer` validates the final effective configuration against the runtime `GatewayFactory` schema so invalid configurations fail at build/CI time.
6. **Atomic Output & Input Protection**
   Composed configurations are written atomically via temporary file creation and `ATOMIC_MOVE`. Composition refuses to overwrite any input file (`--gateway-config` or `--adopter`).
7. **License & Hash Provenance**
   Every generated file includes the repository's Aegis Apache 2.0 license header followed by `# Generated by Aegis ConfigComposer` and `# effectiveConfigHash: sha256:...`.

---

## Pilot Example: Apache Hive

The Hive adopter layout demonstrates the two-layer pattern:

    examples/adopters/hive/aegis/
    ├── gateway.yaml     # Shared gateway governance, policy, rate limits, egress, and audit
    ├── adopter.yaml     # Flat Hive HS2 JDBC source and Hive-bound sql-readonly hook (sources: [hive])
    └── canonical.yaml   # Reference snapshot matching adopters/configurations/adapters/hive/hive.yaml

### Regenerate `hive.yaml`

    java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
      --compose \
      --gateway-config examples/adopters/hive/aegis/gateway.yaml \
      --adopter examples/adopters/hive/aegis/adopter.yaml \
      --out adopters/configurations/adapters/hive/hive.yaml

### CI Drift Check
CI enforces that `adopters/configurations/adapters/hive/hive.yaml` stays synchronized with its source layers during the standard build job:

    - name: Check composed Hive configuration is current
      run: |
        java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \
          --compose \
          --gateway-config examples/adopters/hive/aegis/gateway.yaml \
          --adopter examples/adopters/hive/aegis/adopter.yaml \
          --out adopters/configurations/adapters/hive/hive.yaml \
          --check

---

## Onboarding a New Adopter

1. Create `examples/adopters/<name>/aegis/gateway.yaml` (or reuse a shared gateway baseline) and `examples/adopters/<name>/aegis/adopter.yaml` with the Aegis Apache 2.0 license header.
2. Materialize `adopters/configurations/adapters/<name>/<name>.yaml` via `--compose`.
3. Add a `--check` verification step for `<name>.yaml` in CI.