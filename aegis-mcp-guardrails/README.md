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
# aegis-mcp-guardrails

Optional project-owned governance hook (`hooks[].type: project-governance`). Use it when an Apache
(or other) project wants extra evidence or protected-resource checks on top of the engine chain.

## What it does

The hook adds two narrow checks to governed operations. It denies any operation whose typed resources
match a configured protected-resource glob, whatever the caller or tool class. For the tool classes
you list, it also requires an evidence reference argument (by default `evidenceId`) and checks only
its shape: length, no `..`, no control characters. It does not fetch the evidence, judge whether it is
enough, or approve anything. Policy, approval, dry-run receipts and audit still run around it, and all
of them must still allow the call.

## How it works end to end

1. **Discovery.** `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.GovernanceHookFactory`
   names `ProjectGovernanceGuardrailFactory`. At startup `GatewayFactory` (aegis-config) reads each
   `hooks[]` entry and uses ServiceLoader to find the factory whose `type()` equals the entry's
   `type`. Without this jar on the classpath, `project-governance` is an unknown hook type and startup
   fails.
2. **Configuration.** The gateway, not the factory, reads the binding keys of the entry: `mode`
   (`ENFORCE` by default, `AUDIT` or `OFF`), `operations` (`TOOL_CALL`, `RESOURCE_READ`, `PROMPT_GET`,
   `COMPLETION`; absent means all four) and the conditions `methods` (globs), `groups` and `sources`.
   This hook has no `phases` key; that is a setting of the built-in `remote` hook. The factory reads
   only `settings`:

   | Setting | Default | Read as |
   | --- | --- | --- |
   | `requireEvidenceFor` | `[]` | Tool classes `READ`, `MUTATE`, `DESTRUCTIVE` (any case) |
   | `evidenceArgument` | `evidenceId` | Argument name matching `[A-Za-z][A-Za-z0-9_]{0,63}` |
   | `minEvidenceLength` | `8` | Shortest accepted reference, 1 to 512; the longest is always 512 |
   | `protectedResources` | `[]` | Globs (`*`, `?`) over typed resources, for example `project:governance:*` |

   Any other key under `settings` stops startup, because `ConfigNode.finish()` rejects unread keys.
3. **Position in the chain.** For `tools/call`, `GovernanceEngine` runs exposure (1), input and scope
   (2), policy (3), egress (5), rate and budget (6) and breaker (7). Step 8 is the argument injection
   screen followed by every hook in configured order through `HookRunner.before`. Approval (4), the
   dry-run receipt (9), commit and execution (10) come after the hooks. `ContentGovernor` calls the
   same `HookRunner` at its own step 8 for `resources/read`, `prompts/get` and `completion/complete`.
   `HookRunner` reports a hook denial with step 8.
4. **Which calls it sees.** `HookBinding.applies` skips the hook when `mode` is `OFF`, or when the
   operation, method, caller group or source id does not match the configured conditions.
5. **What it inspects.** `ProjectGovernanceGuardrail.before` reads `context.action()` and
   `context.arguments()`:
   - every typed resource of the action (`type:value`, for example `project:release:2.0`) is matched
     against `protectedResources`. Content operations carry class `READ` and their own typed
     resources, so this check applies to them as well when the binding includes them;
   - if the action's tool class is in `requireEvidenceFor`, the argument named by `evidenceArgument`
     must be a string of `minEvidenceLength` to 512 characters with no `..` and no ISO control
     character (`Character.isISOControl`).
6. **What it returns.** `Decision.allow()`, or `Decision.deny` with one of two fixed codes:
   `POLICY_DENIED` for a protected resource or a glob limit, `INVALID_INPUT` for a missing or
   malformed reference. The reason names the resource, or the argument and the tool class. The hook
   implements only the before phase; tool results and content pass through it unchanged.
7. **ENFORCE, AUDIT and failure.** In `ENFORCE` the denial stops the call with the hook's code and the
   reason `hook project-governance: ...`, and the backend is not called. In `AUDIT` the call continues
   and `project-governance: would deny (...)` is written to the audit record under `hookAudit`. If the
   hook throws, `HookRunner` denies with `POLICY_DENIED` in `ENFORCE` and records
   `project-governance: failed (<ExceptionType>)` in `AUDIT`.

Minimal configuration, taken from the example profile. The reference is an ordinary tool argument and
step 2 checks arguments against the tool schema before hooks run, so declare it in each tool's
`inputSchema` (the profile uses `evidenceId: {type: string, minLength: 8, maxLength: 512}`).

```yaml
hooks:
  - type: project-governance
    mode: ENFORCE                # use AUDIT first to see what it would deny
    operations: [TOOL_CALL]
    settings:
      requireEvidenceFor: [MUTATE, DESTRUCTIVE]
      evidenceArgument: evidenceId
      minEvidenceLength: 8
      protectedResources: ["project:governance:*"]
```

```text
tools/call
  steps 1, 2, 3, 5, 6, 7    exposure, input and scope, policy, egress, rate and budget, breaker
  step 8                    argument injection screen, then hooks[] in configured order
    project-governance      a resource matches protectedResources       -> deny POLICY_DENIED
                            glob limit exceeded                          -> deny POLICY_DENIED
                            tool class not in requireEvidenceFor         -> allow
                            reference missing, not a string, too short,
                            over 512, contains "..", control character   -> deny INVALID_INPUT
                            otherwise                                    -> allow
  steps 4, 9                approval, dry-run receipt
  commit, step 10           consume approval and receipt, audit, execute
```

## Main types

| Type | Module | Role |
| --- | --- | --- |
| `ProjectGovernanceGuardrailFactory` | this module | ServiceLoader factory; `type()` is `project-governance`; parses `settings` and turns bad values into `ConfigException` |
| `ProjectGovernanceGuardrail` | this module | The `GovernanceHook`: `name()` is `project-governance`, logic is in `before()` |
| `ProjectGovernanceGuardrail.Settings` | this module | Immutable record; applies the `evidenceId` default and the identifier and length bounds |
| `GovernanceHookFactory` | aegis-config | SPI the factory implements |
| `GovernanceHook` | aegis-engine | Hook contract and the read-only `Context` |
| `HookBinding` | aegis-engine | One entry's mode, operations, methods, groups and sources |
| `HookRunner` | aegis-engine | Runs bindings in order, keeps allowed codes, fails closed in `ENFORCE` |
| `Glob` | aegis-engine | Bounded `*` and `?` matcher used for `protectedResources` |

## Failure behaviour

- An unknown `requireEvidenceFor` value stops startup with
  `<path>.requireEvidenceFor must name READ, MUTATE or DESTRUCTIVE`.
- An `evidenceArgument` that is not an identifier, or a `minEvidenceLength` outside 1 to 512, stops
  startup with the settings path and the reason. A blank or absent `evidenceArgument` becomes
  `evidenceId`.
- A missing jar stops startup with `unknown hook type 'project-governance'`.
- A reference that is not a string (a number, a list, an object) counts as missing: `INVALID_INPUT`.
- A glob limit (more than 128 patterns, a pattern over 512 characters or 64 `*`, a resource over 4096
  characters, or more than 65536 matcher steps) denies with `POLICY_DENIED`; it is never read as "no
  match". Pattern limits are checked while matching, not at startup, so an oversized pattern denies
  every bound call that carries a resource.
- Any exception from the hook denies with `POLICY_DENIED` and `hook project-governance failed
  (<ExceptionType>)` in `ENFORCE`; in `AUDIT` it is recorded and the call continues.
- `POLICY_DENIED` and `INVALID_INPUT` are both on the `HookRunner` allow list, so the caller sees the
  hook's code unchanged.

## Tests

- `ProjectGovernanceGuardrailTest` (this module):
  - `highImpactActionsNeedBoundedEvidenceAndProtectedResourcesAlwaysDeny`: a `DESTRUCTIVE` action
    without evidence is denied; a short reference on `MUTATE` gives `INVALID_INPUT`; a reference with
    `..` is denied; `docs/evidence/release-2.0.md` is allowed; a protected resource denies even a
    `READ` action; an oversized resource hits the glob limit and returns `POLICY_DENIED`.
  - `factoryParsesOnlyKnownClassesAndBounds`: the factory type is `project-governance`, valid
    settings build a guardrail, and an unknown class or `minEvidenceLength: 0` throws
    `ConfigException`.
- Related tests outside this module:
  - `HooksTest` (aegis-engine) covers the generic hook rules this hook relies on: `ENFORCE` denies
    and `AUDIT` only records, `OFF` never runs, conditions narrow a hook, order is kept and the first
    denial wins.
  - `ApacheProjectGovernanceProfileTest` (examples/aegis-examples) builds the example profile with
    the real loader, so the `project-governance` hook is found through ServiceLoader, and checks tool
    visibility for contributor and maintainer roles.

## Developer instructions

```powershell
mvn -B -pl aegis-mcp-guardrails -am test
```

1. Hook failures in ENFORCE mode must deny; never fail open.
2. Keep project-specific policy out of typed sources; put it here or in `adopters/hooks`.
3. Reference profile: `adopters/profiles/apache-project-governance.example.yaml`. It is illustrative
   only, not an ASF policy or release rule; see
   [the profile notes](../docs/CONFIGURATION.md#illustrative-apache-project-governance-profile).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-mcp-guardrails)
- [Configuration: `type: project-governance`](../docs/CONFIGURATION.md#type-project-governance-evidence-on-high-impact-actions)
- [Adopter hooks](../adopters/hooks/README.md)
- [AGENTS.md](../AGENTS.md)
