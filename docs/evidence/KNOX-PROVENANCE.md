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

The `aegis-knox3-extension` compiles against Apache Knox 3.0.0 public APIs in Maven `provided` scope,
which Knox supplies at runtime. The shared Aegis engine, MCP handler, and configuration modules are
intended to remain host-neutral. Current packaging and import boundaries are executable assertions,
but they are `recorded-unattested` until their output is retained against a reviewed candidate.

## Preserved historical comparison

[`knox-source-comparison.json`](knox-source-comparison.json) is a `verified-historical` snapshot
generated on `2026-09-29T17:55:38Z`. It binds these earlier inputs:

| Input | Revision | Production Java files | Source-tree SHA-256 |
| --- | --- | ---: | --- |
| Aegis | `91c482e5b3256ced22398e820013ada182f959a3` | 122 | `5d26eeaac98e34e966a5c8571cc0f20c1b5952204b3197485371631f396e0fce` |
| Apache Knox | `7d4e4facc09318354d51bcee9b2f8d248dfca638` | 1,362 | `8d60713a54d79a2288e390deeaebeb87bd72e20813ce04059f84529f679c148a` |

At those revisions, the preserved report found:

- zero exact 60-token Java windows;
- zero Knox imports outside `aegis-knox3-extension`;
- zero Knox classes packaged in an Aegis jar;
- 22 exact four-line windows requiring human review.

The 22 short matches reduce to two conventional public-API shapes in the extension: Knox service
contributors implement `getRole()` and `getName()`, and JAX-RS resources inject request and servlet
contexts with `@Context`. The report preserves every location instead of suppressing them. These
short interface implementations are not evidence that the shared governance engine came from Knox.

This 2026-09-29 report does not establish the boundary or production-file count for the current source
tree. Current claims must use a new report bound to the candidate under review.

## Reproducible local check

The repository includes `tools/provenance/compare_knox_source.py`. It compares production Java in
this tree with a local Knox checkout, without sending source to another service. Preserve a new,
commit-addressed report rather than overwriting the historical snapshot:

```powershell
python tools/provenance/compare_knox_source.py `
  --knox-root C:\path\to\apache-knox `
  --report docs\evidence\knox-source-comparison-<candidate>.json
```

The current `KnoxPackagingBoundaryTest` separately asserts the present structural packaging boundary.
That current assertion is `recorded-unattested` because no candidate-bound raw build output and
manifest are retained here.

## What this does and does not establish

The historical check provides reviewable evidence about exact source overlap and the compiled
dependency boundary at its recorded revisions. It does not prove authorship, originality, ownership
or license compliance, and it does not detect rewritten or translated code. The separate provenance
scanner required by `AGENTS.md` remains a release gate.

A reviewer should create a new comparison when Knox-extension code changes, when the Knox baseline
changes, or before making a release provenance statement. A long exact token match blocks the script
until it is reviewed; short line matches remain visible for human judgment.
