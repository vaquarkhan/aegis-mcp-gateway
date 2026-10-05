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
# Deny codes

The codes and step numbers are a public contract. They appear in audit records as
`DENIED:<code>:step<N>`, in `_meta["io.github.vaquarkhan.aegis/decision"]`, and in dashboards.
2.0 reuses every 0.1 code (0.1 `interceptor/Decision.java`, preserved in Git history) verbatim, and adds two
codes that need maintainer review before they become part of the registry.

| Code | Step | 0.1? | When |
| --- | --- | --- | --- |
| `NOT_EXPOSED` | 1 | yes | Unknown tool, or the tool's `allowedGroups` excludes the caller |
| `READONLY_CALLER` | 1 | yes | MUTATE/DESTRUCTIVE tool and the caller is not in write/destructive groups |
| `CATALOG_INTEGRITY` | 1 | **2.0 addition** | Tool quarantined: definition changed since pinned, unpinned under REQUIRE_PINS, or poisoned description |
| `INVALID_INPUT` | 2 | yes | Ambiguous or mistyped control values, schema violation, dry run on a READ tool |
| `SCOPE_DENIED` | 2 | yes | Resource argument missing or unsafe (`..`, wildcard, control characters), or out of scope |
| `POLICY_DENIED` | 3 | yes | Policy denies or fails. Hook denials use it at step 8, including any hook that returns an unregistered code. At step 10 it withholds a tool result with binary parts when `output.allowBinary` is false |
| `APPROVAL_REQUIRED` | 4 | yes | No, invalid, expired, mismatched or already-used approval token; approval not configured |
| `EGRESS_DENIED` | 5 | yes | Destination not allowed, or any DNS answer in a denied class, or the target cannot be determined |
| `RATE_LIMITED` | 6 | yes | Per-caller or per-caller-per-tool rate |
| `BUDGET_EXCEEDED` | 6 | yes | Weighted hourly budget |
| `BREAKER_OPEN` | 7 | yes | Source circuit open |
| `PROMPT_INJECTION` | 8 | yes | Screening in ENFORCE mode matched (hooks may also use it) |
| `VRP_FAILED` | 9 | yes | DESTRUCTIVE without a valid, matching, unused dry-run receipt; VRP not configured |
| `AUDIT_UNAVAILABLE` | 10 | **2.0 addition** | The intent could not be journaled, so the call was not executed |
| `TIMEOUT` | 10 | yes | Backend timed out |
| `BACKEND_ERROR` | 10 | yes | Backend or transport failure; credentials could not be resolved |

Notes:

- Approval is evaluated after steps 5 to 8 (see ARCHITECTURE.md section 4) but keeps step number 4.
- `tools/call` denials are tool results with `isError: true` and the decision in `_meta`, so the
  agent can read the reason (and the approval request id).
- Resources, prompts and completion use the same codes and steps. Because their MCP results have
  no `isError`, a denial is the JSON-RPC error **-32010** (HTTP 200) with
  `data: {code, step, reason, requestId}`. Content-specific uses: `NOT_EXPOSED` also covers a
  resource URI that no source serves or that two sources' `uriPatterns` both match;
  `CATALOG_INTEGRITY` a quarantined prompt; `PROMPT_INJECTION` (step 8) returned content that
  matches the content screen; `POLICY_DENIED` at step 10 binary content on a source without
  `allowBinary`; `BACKEND_ERROR` a malformed upstream result or a read that returns a URI outside the
  source's patterns.
- Extension hooks reuse the registry: a hook may deny with `POLICY_DENIED`, `INVALID_INPUT` or
  `PROMPT_INJECTION` and nothing else (any other code becomes `POLICY_DENIED`). A before-phase hook
  denies at step 8; a hook that withholds content denies at step 10. A hook whose mode is `AUDIT`
  produces no deny code at all: the would-be denial is recorded in the audit field `hookAudit` and the
  request continues.
- Protocol-level rejections are JSON-RPC errors, not deny codes. Examples are header mismatch
  (-32020), an unsupported version (-32022), and a parse error.
- Own-host perimeter rejections happen before the engine sees a request, so they are HTTP statuses,
  not deny codes: 401 with a `WWW-Authenticate` challenge when identity is missing or invalid, and
  403 when the caller is outside `host.routes.mcpGroups` or `host.routes.adminGroups`. A browser
  Origin outside `server.allowedOrigins` is also an HTTP 403, on every host.
