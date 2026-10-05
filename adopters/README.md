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
# Adopters

This directory is the intended product-facing home for material a project adopts: reviewed profiles,
reference configurations, client integration examples and runnable tutorials.

The Maven example module remains under `examples/aegis-examples` because it contains production plugin
source and test infrastructure. Its adoption configurations are now read from this directory through a
single test locator, so the product-facing files have one canonical home while the Maven module remains
an implementation/test concern.

## Target layout

```text
adopters/
  profiles/        project-owned guardrail/profile examples
  configurations/  reference MCP and engine configurations
    adapters/
      <adopter>/
        <adopter>.yaml  executable adopter profile
        README.md       developer guide for this adopter
  clients/         language/client integration examples
  frameworks/      framework-to-Aegis examples
  tutorials/       runnable end-to-end tutorial material
```

The first adopter artifact is the project governance profile:
[`../adopters/profiles/apache-project-governance.example.yaml`](../adopters/profiles/apache-project-governance.example.yaml).

Adoption paths now use this directory. Each 2.0 adapter profile lives in its own named directory under
`adopters/configurations/adapters/<adopter>/`. The directory contains the executable YAML and a
README explaining the backend surface, source choice, evidence level and developer workflow. The 0.1
`mcp-adapter-*` Java modules are not part of 2.0; [docs/HISTORY.md](../docs/HISTORY.md) maps them to
these profiles.
