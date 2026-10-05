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
# Apache Knox source-boundary evidence

The `aegis-knox3-extension` compiles against Apache Knox 3.0.0 public APIs in Maven `provided` scope, which Knox supplies at runtime. The shared Aegis engine, MCP handler, and configuration modules remain host-neutral, and the Aegis artifacts retain no `org/apache/knox` classes.

## Reproducible local check

The repository includes `tools/provenance/compare_knox_source.py`. It compares production Java in
this tree with a local Knox checkout, without sending source to another service:

```powershell
python tools/provenance/compare_knox_source.py `
  --knox-root C:\path\to\apache-knox `
  --report docs\evidence\knox-source-comparison.json
```

The preserved result in [`knox-source-comparison.json`](knox-source-comparison.json) used Apache Knox
commit `7d4e4facc09318354d51bcee9b2f8d248dfca638` and exact source-tree digests for both inputs. It found:

- zero exact 60-token Java windows;
- zero Knox imports outside `aegis-knox3-extension`;
- zero Knox classes packaged in an Aegis jar;
- 22 exact four-line windows requiring human review.

The 22 short matches reduce to two conventional public-API shapes in the extension: Knox service
contributors implement `getRole()` and `getName()`, and JAX-RS resources inject request and servlet
contexts with `@Context`. The report preserves every location instead of suppressing them. These
short interface implementations are not evidence that the shared governance engine came from Knox.

## What this does and does not establish

This check provides reviewable evidence about exact source overlap and the compiled dependency
boundary. It does not prove authorship, originality, ownership or license compliance. It does not
detect rewritten or translated code. The separate provenance scanner required by `AGENTS.md` remains
a release gate.

A reviewer should rerun this comparison when Knox-extension code changes, when the Knox baseline
changes, or before making a release provenance statement. A long exact token match blocks the script
until it is reviewed; short line matches remain visible for human judgment.
