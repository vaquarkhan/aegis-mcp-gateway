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
# MCP governance guideline

This document is the Aegis operator guideline for governing Model Context Protocol (MCP)
interfaces. It describes the controls a project or deployment team should define before an
agent can discover, invoke, or receive results from an MCP capability.

It is **not** an Apache Software Foundation policy, a release procedure, legal advice, or a
compliance certification. Apache projects govern themselves through their PMCs and remain
responsible for their own identity, privacy, security, release, and operational decisions. An
Aegis deployment provides a technical control boundary and evidence, not a conclusion that a
project complies with a law, standard, or Foundation requirement.

## 1. Definition and scope

MCP governance is the set of controls that determines whether a caller may discover, invoke,
delegate, approve, and audit an MCP capability. The decision must use the action's semantics,
not only the HTTP route or an authenticated client identity:

- **who** is acting and on whose delegated authority;
- **what** named tool, resource, prompt, or completion operation is requested;
- **which data or system resource** the request affects;
- **which arguments and capability definition** are being relied on;
- **whether a human approval or preview receipt is required**; and
- **what evidence records the decision and result**.

MCP governance complements, rather than replaces, transport security, API gateways, backend
access control, model-provider controls, and project governance. Those layers must continue to
perform their own roles.

Aegis applies this guideline at the MCP execution boundary. It does not decide whether an
agent's business goal or intent is good, prevent every prompt-injection attack, replace a
least-privilege backend credential, or control data after a client receives it.

## 2. Guiding principles

An Aegis deployment should apply these principles together. No single control is sufficient.

| Principle | Required operator outcome | Aegis control boundary |
| --- | --- | --- |
| Semantic decision | A decision is made for a named MCP operation, its arguments, and typed resources. | Tool descriptors, schemas, typed-resource extraction, policy, and action binding. |
| Least privilege | Access starts denied and expands only through explicit policy and narrow backend credentials. | Default deny, deny-overrides, groups, scopes, resource rules, and source credentials. |
| Bounded delegation | An agent may act only with explicit, limited authority associated with an authenticated principal. | Host identity, effective subject, delegation chain, route groups, and OAuth scopes. |
| Human control | Consequential changes require independent human review before backend execution. | Per-call approval and destructive-operation preview receipts. |
| Trusted capability surface | A changed or unreviewed tool definition cannot silently change the available authority. | Catalog pinning, quarantine, source trust state, and explicit acceptance. |
| Safe data flow | Untrusted content cannot expand authority; sensitive content is bounded and protected. | Schema validation, resource validation, screening, output bounds, redaction, and egress policy. |
| Fail closed | An unavailable, ambiguous, or invalid control prevents execution. | Fixed governance order, hook failure denial, audit intent before execution, and strict configuration. |
| Verifiable accountability | The operator can reconstruct the decision, approval, and outcome for governed actions. | Intent-first hash-chained audit records and offline verification. |

## 3. Baseline controls for an MCP capability

Before exposing an MCP capability through Aegis, its owning team should document the following
items. A missing item is a reason to delay exposure or classify the capability more strictly.

### 3.1 Capability ownership and contract

Each exposed tool, resource, prompt, or completion path should have:

1. a named owning team and escalation contact;
2. an explicit purpose and supported caller population;
3. a stable identifier and input schema;
4. an action class of `READ`, `MUTATE`, or `DESTRUCTIVE`;
5. typed resources extracted from validated arguments where the operation targets a system or
   data object;
6. an explicit backend identity or credential model; and
7. a change-review process for the tool definition and its policy.

In Aegis, an unclassified proxied tool is `DESTRUCTIVE`. That conservative default prevents a
newly discovered capability from receiving write-like authority by omission. A source supplies
capability only. The operator supplies classification, resource taxonomy, approval rules, and
policy.

### 3.2 Identity and delegation

The deployment should authenticate the caller at the host boundary and preserve an accountable
identity record containing the actor, effective subject, groups, issuer, and any delegation
chain. Authentication establishes who presented a token; it does not itself authorize a tool
call.

For delegated requests, the deployment should define the delegation issuer, audience, scope,
expiry, and revocation behavior. A token valid for one MCP server or action class should not
silently become authority for another. Aegis own-host deployments can bind OAuth resource
metadata, audiences, route groups, and scopes at the perimeter, then apply tool and resource
policy inside the governance engine.

Do not forward the caller's credential to a backend as a substitute for semantic authorization.
Aegis resolves the backend credential only after it allows and journals the action. The backend
credential must still be least privilege for the source it serves.

### 3.3 Semantic authorization and resource scope

Authorization should evaluate the complete governed operation:

- caller identity, groups, and delegation context;
- tool or MCP operation name and operator-defined action class;
- source and backend target;
- validated arguments and canonical argument digest; and
- typed resources, such as `kafka:topic:orders` or `iceberg:table:sales.orders`.

A policy that allows `POST /mcp` or a broad backend service account is not a substitute for this
decision. Aegis starts with default deny, gives explicit denies precedence, and refuses missing,
malformed, wildcard, or widened typed resources rather than converting them to broad access.

### 3.4 Approval and safe execution

The operator should identify state-changing and high-impact operations before exposing them.
Aegis provides the following baseline:

| Action class | Minimum Aegis control |
| --- | --- |
| `READ` | Standard identity, exposure, schema, typed-resource, policy, egress, screening, and audit decision path. |
| `MUTATE` | The `READ` controls plus independent, single-use, per-call approval bound to the exact action. |
| `DESTRUCTIVE` | The `MUTATE` controls plus a fresh preview or dry-run receipt bound to the same action. |

Approval and receipt binding must include the caller, tool, source, class, typed resources,
argument digest, and tool-definition digest. A requester and its delegated identity must not
approve their own action. A preview is not a valid receipt if it cannot safely represent the
backend action that will follow.

A project may define a narrower exception for low-risk automation, but it should be public,
reviewed, reversible, and backed by clear evidence. Aegis does not make autonomous state changes
safe merely because a client or tool labels them routine.

### 3.5 Capability and source integrity

MCP clients often discover tool names, descriptions, schemas, resources, and prompts dynamically.
Those definitions are security-relevant because they influence both agent behavior and the
available action surface. The deployment should:

1. register only reviewed sources and endpoints;
2. pin reviewed tool and prompt definitions where the source can change independently;
3. quarantine a definition change until an authorized operator accepts it;
4. retain evidence for initial source admission and later acceptance; and
5. review the source's authentication, backend authority, network path, and update model.

A pin detects changes after an initial definition is trusted. It does not prove that a first
observation was safe. Initial source review remains an operator responsibility.

### 3.6 Data, content, and network controls

MCP arguments, tool results, resources, prompts, tickets, logs, and retrieved documents may all
contain attacker-controlled instructions. Treat this content as data, never as authority to alter
policy, credentials, approval requirements, source trust, or egress rules.

An Aegis deployment should:

- validate schemas and typed resources before source execution;
- apply input and output screening as a risk-reduction control, not a claim of complete
  prompt-injection prevention;
- bound request and response size, redact recognized secrets, and refuse binary content unless
  explicitly allowed;
- minimize argument/result disclosure to remote hooks and other third parties;
- use the egress allowlist for every HTTP destination, DNS answer, and nested URL; and
- restrict source plugins and backend processes with ordinary network, filesystem, process, and
  credential controls outside the gateway.

A remote hook is a separate data recipient. By default, Aegis sends it identity, operation,
typed resources, and an argument digest, not raw arguments or results. An operator must explicitly
opt in before such content leaves the gateway.

### 3.7 Audit, evidence, and operational response

For every governed call, record enough information to explain the decision without indiscriminately
recording sensitive raw content. The evidence should identify the caller, effective subject,
delegation context, tool, source, action class, typed resources, argument digest, policy result,
approval/receipt references, tool-definition digest, outcome, timestamp, and trace identifier.

Aegis writes an `ALLOW_INTENT` event before resolving the backend credential or calling the
backend. If that audit intent cannot be recorded, the call does not execute. Its local hash chain
and offline verifier support detection of local-history tampering, but do not replace retention,
access control, monitoring, alerting, or external audit anchoring. Those remain deployment
responsibilities.

The operator should define an incident process for revoked identities, compromised backend
credentials, suspect tool definitions, audit-verification failures, denied-call investigation, and
MCP-specific vulnerability disclosure. Apache software vulnerabilities continue to use the
Foundation's coordinated security process where applicable.

## 4. Aegis adoption workflow

Use this workflow for a new source or an existing MCP server.

1. **Inventory the action surface.** List the real tools, resources, prompts, completion paths,
   source endpoints, backend effects, owners, and caller groups. Do not claim support from a
   configuration shape alone.
2. **Define the resource taxonomy.** Give each protected object a typed resource form and reject
   ambiguous or wildcard values.
3. **Classify actions conservatively.** Start new or uncertain actions as `DESTRUCTIVE`; relax
   only after evidence supports a lower class.
4. **Bind identity to policy.** Configure the host identity provider, group mapping, OAuth
   audience/scopes where used, and route admission. Define how delegation is represented and
   revoked.
5. **Apply explicit policy.** Configure exposure, default-deny rules, narrow resource access,
   source-specific backend credentials, and egress allowlists.
6. **Require approvals and receipts.** Configure independent approvers for state changes and a
   safe preview model for destructive actions.
7. **Review capability integrity.** Admit sources deliberately, establish catalog pins, and
   define who can accept an intentional definition change.
8. **Protect data flow.** Set request/response bounds, output redaction, screening, hook data
   disclosure, and external network restrictions.
9. **Establish evidence and operations.** Select durable state where required, protect the audit
   store, set retention and external anchoring, and test verification and incident procedures.
10. **Validate against the target.** Run deterministic configuration tests and a retained
    target-version run before claiming a real integration is verified.

The illustrative
[Apache-project governance profile](../adopters/profiles/apache-project-governance.example.yaml)
shows a configuration shape for a project community to adapt. It is not an ASF policy, release
checklist, or proof of compliance. The
[configuration guide](CONFIGURATION.md#type-project-governance-evidence-on-high-impact-actions)
and [adoption guide](ADOPTION-GUIDE.md) explain the configuration and validation boundaries.

## 5. Shared responsibility

| Responsibility | Aegis can enforce or evidence | The deployment or project must own |
| --- | --- | --- |
| Perimeter | Host identity, route groups/scopes, request bounds, CORS configuration. | IdP lifecycle, credential issuance, TLS termination, network topology, and recovery. |
| Action governance | Tool class, typed resources, policy, approval, receipt binding, catalog integrity, and decision audit. | Accurate classification, resource taxonomy, approver roles, policy review, and source admission. |
| Backend safety | Blocks a request before execution and resolves credentials only after governance succeeds. | Least-privilege backend roles, native authorization, transaction safety, sandboxing, and rollback. |
| Data protection | Schema limits, egress enforcement, screening, content bounds, and recognized-secret redaction. | Data classification, retention, privacy review, key management, DLP, model-provider terms, and downstream handling. |
| Evidence | Intent-first local hash chain, structured decision records, and offline verification. | Secure storage, retention, off-host anchoring, access review, alerting, incident response, and legal obligations. |

## 6. Relationship to ASF governance

ASF policy already establishes the project-level controls that matter to an Apache community:
public and archived technical decisions, independent PMC governance, secure vulnerability handling,
release voting, source and signature verification, licensing, privacy, and contributor
accountability. The official ASF policy catalog does not establish a Foundation-wide runtime
MCP-governance profile.

This guideline fills only the runtime gap for an Aegis deployment. It does not change ASF policy,
replace a PMC's technical judgment, or require any Apache project to adopt Aegis. A project that
chooses Aegis should govern its configuration and operational exceptions through its normal public
processes, while keeping embargoed vulnerability information private under the applicable security
procedure.

Relevant ASF sources:

- [ASF policy index](https://www.apache.org/foundation/policies/)
- [Board project services and expectations](https://apache.org/board/policies.html)
- [ASF Release Policy](https://www.apache.org/legal/release-policy.html)
- [ASF Project Security for Committers](https://www.apache.org/security/committers.html)
- [ASF Third-Party License Policy](https://www.apache.org/legal/resolved.html)
- [ASF Generative Tooling Guidance](https://apache.org/legal/generative-tooling.html)
- [ASF Privacy Policy](https://privacy.apache.org/policies/privacy-policy-public.html)

The source material is summarized and rephrased for compliance with licensing restrictions.

## 7. Evidence, not certification

An Aegis deployment can demonstrate that configured controls load, deny or allow defined cases,
record decisions, and work against a retained target version. That evidence is valuable, but it is
not certification, legal advice, a security guarantee, or proof of compliance with a regulation or
an ASF requirement.

Use [VALIDATION.md](VALIDATION.md), [SECURITY.md](SECURITY.md),
[GOVERNANCE-MAPPING.md](GOVERNANCE-MAPPING.md), and the project-specific threat model to state
exactly what was tested, what controls are configured, and which responsibilities remain outside
the gateway.
