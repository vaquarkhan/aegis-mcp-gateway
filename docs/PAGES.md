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
# GitHub Pages publishing notes

`docs/index.html` is the public landing page and `docs/guide/handbook.html` is the task-focused user handbook. Both use local CSS and static visual assets; no Node, Ruby, Python, or container build is required to render the landing page itself. Linked Markdown pages are rendered by GitHub Pages through Jekyll.

After a human maintainer commits the 2.0 package, enable GitHub Pages with **Deploy from a branch** and select the branch and `/docs` directory that contain these files. This repository intentionally has no automated publishing workflow: publication makes material public and must remain a reviewed human action.

The `docs/evidence/` directory is published so that the landing page’s claims-matrix link is real rather than a dead public link. Evidence files must remain redacted, license-reviewed, and appropriate for public distribution. Documents outside `docs/`, such as `examples/adopters/`, work in the repository view but are not served by Pages; the handbook and landing page label those as repository links.

Before publishing a release claim:

1. Run `mvn -B clean install`. The documentation tests verify static assets, local links, anchors, canonical profile copies, and encoding. Mermaid blocks are always checked for presence and rendered when a local Mermaid CLI is supplied; the default build intentionally does not download a renderer.
2. Read [VALIDATION.md](VALIDATION.md), [evidence/CLAIMS-MATRIX.md](evidence/CLAIMS-MATRIX.md),
   [THREAT-MODEL.md](THREAT-MODEL.md), and [evidence/SECURITY-VALIDATION.md](evidence/SECURITY-VALIDATION.md)
   to confirm evidence status, threat-model scope, and current counts.
3. Open the published handbook and each primary navigation link. Confirm that references to repository-only material identify that boundary.
