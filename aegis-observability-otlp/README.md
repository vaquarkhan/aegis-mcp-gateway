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
# aegis-observability-otlp

Optional OTLP/HTTP JSON span exporter for governance decisions. Enabled only when
`tracing.otlp.endpoint` is set and passes egress at startup. Observer failures never change a
decision.

## What it does

`OtlpSpanExporter` turns each governance outcome into one OpenTelemetry span and posts spans in
batches, as JSON, to an OTLP/HTTP traces endpoint. It is a `DecisionListener`, so it only observes and
cannot change a decision. A span carries names, codes and timings; it carries no caller identity,
arguments or results. The request thread only offers the span to a bounded queue, and a background
thread does all network work, so a slow or absent collector neither slows nor changes a call. There is
no OpenTelemetry SDK dependency: the class builds the JSON document itself and sends it with the JDK
`HttpClient`.

## How it works end to end

1. **Wiring.** `GatewayFactory` (aegis-config) reads `tracing.otlp`. Without `endpoint`, no exporter
   is built and no thread starts. With it, the value must parse as a URI and pass the `egress` allow
   list, or startup fails. `tokenEnv` adds `Authorization: Bearer <value>` from the environment, and
   startup fails if that variable is not set. The exporter is constructed only after the whole
   document validated and the audit journal opened, then registered as an engine listener after
   `DecisionMetrics` (when metrics are enabled).
2. **When a span is created.** After an outcome is decided and audited, `GovernanceEngine` (for
   `tools/call`) and `ContentGovernor` (for `resources/read`, `prompts/get`, `completion/complete`
   and content listings) build a `DecisionListener.Event` with a `DecisionListener.Span` (span id,
   parent span id, start time) from the request's W3C trace context. `Listeners.notify` hands it to
   every listener on the request thread. Allowed, denied and failed outcomes all produce an event.
3. **Enqueue.** `onDecision` ignores a null event or an event without a `Span`, then calls `offer` on
   an `ArrayBlockingQueue` whose capacity is `max(batchSize, maxQueue)`. When the queue is full the
   span is dropped and `droppedSpans()` goes up. This path never blocks and does no I/O.
4. **Batch.** The daemon thread `aegis-otlp-exporter` waits up to `flushMs` for a span. When one
   arrives it drains up to `batchSize - 1` more that are already queued and sends them together; it
   does not wait for a batch to fill. So `flushMs` is the idle poll interval, which also bounds how
   soon the thread notices `close()`.
5. **Export.** Each batch is one `ExportTraceServiceRequest` JSON document sent as one `POST` with
   `Content-Type: application/json` plus the configured headers. `timeoutMs` is the connect timeout,
   the request timeout and the total deadline including reading the reply (`BoundedHttp`). The reply
   is capped at 64 KiB and redirects are not followed. A 2xx adds the batch size to
   `exportedSpans()`; anything else adds one to `failedBatches()`. A failed batch is not retried.
6. **Shutdown.** `Gateway.close()` closes the exporter last, after the engine, so the final requests
   still export. `close()` clears the running flag, lets the worker drain what is queued, waits up to
   `timeoutMs + 2 * flushMs`, and only then interrupts a worker still stuck in a send. A non-zero drop
   count is logged at INFO.

```text
request thread
  outcome decided and audited
  Listeners.notify -> OtlpSpanExporter.onDecision(event)
    no span                 ignore
    queue.offer(event)      queue full -> drop, droppedSpans + 1
  response returned (no network I/O on this thread)

aegis-otlp-exporter thread (daemon), while running or queue not empty
  poll up to flushMs for one span, then drain up to batchSize - 1 more
  document(batch, serviceName) -> JSON bytes
  POST endpoint (timeoutMs total, 64 KiB reply cap, no redirects)
    2xx                     exportedSpans + batch size
    anything else           failedBatches + 1, batch discarded
```

### What a span contains

| Field | Value |
| --- | --- |
| `traceId`, `spanId`, `parentSpanId` | From the request's trace context, passed through as hex strings; `parentSpanId` is omitted when the caller sent no `traceparent` |
| `name` | `tools/call <tool>` for a tool call, otherwise the MCP method, for example `resources/read` |
| `kind` | `2` (SERVER) |
| `startTimeUnixNano`, `endTimeUnixNano` | Decimal strings; the end is the start plus the gateway's duration |
| `status` | Code `0` (UNSET) when allowed; code `2` (ERROR) with the deny code as `message` otherwise |
| Attributes | `aegis.request_id`, `aegis.kind` (`tool` or `content`), `aegis.outcome` (`ALLOWED` or the deny code), `mcp.method.name`, `aegis.tool` (tool calls only), `aegis.tool_class` and `aegis.source` (when not empty), `aegis.step` (0 when allowed), `aegis.reached_backend` |
| Resource and scope | `service.name` set to `serviceName`; scope `io.github.vaquarkhan.aegis`, version `2.0.0` |

A span does **not** contain caller identity (actor, groups, issuer), credentials or tokens, arguments
or their digest, typed resources, or results and content. `DecisionListener.Event` has no field for
any of them. Join a span to the audit journal on `aegis.request_id` when you need who and what.

OTLP/JSON expects hex trace and span ids rather than the base64 of the protobuf JSON mapping, and the
exporter sends hex. The document shape is checked against a local test endpoint, not a live
collector, so confirm compatibility with your own collector.

### Configuration

| Key under `tracing.otlp` | Default | Notes |
| --- | --- | --- |
| `endpoint` | none | Turns the exporter on; must be a URI allowed by `egress` |
| `serviceName` | `gateway.name` | A blank value becomes `aegis` in the exporter |
| `tokenEnv` | none | Environment variable holding a bearer token |
| `headers` | `{}` | Static string headers added to every POST |
| `timeoutMs` | `5000` | Connect timeout and total deadline per batch |
| `maxQueue` | `2048` | Queue bound; the capacity is never below `batchSize` |
| `batchSize` | `128` | Most spans per POST; values below 1 become 1 |
| `flushMs` | `2000` | Idle poll interval; 0 or less becomes 2000 |

Any other key fails startup. So does setting the other keys without `endpoint`, because they are
then never read and `ConfigNode.finish()` rejects unread keys. See also
[Configuration: controls](../docs/CONFIGURATION.md#controls).

```yaml
tracing:
  otlp:
    endpoint: http://otel-collector:4318/v1/traces   # must also be allowed by egress
    serviceName: aegis-kafka
    tokenEnv: OTLP_TOKEN                             # optional
```

## Main types

| Type | Module | Role |
| --- | --- | --- |
| `OtlpSpanExporter` | this module | Listener, bounded queue, worker thread, JSON document builder; counters `droppedSpans()`, `exportedSpans()`, `failedBatches()` |
| `DecisionListener`, `DecisionListener.Event`, `DecisionListener.Span` | aegis-engine | Observer SPI and the event it receives |
| `Listeners` | aegis-engine | Delivers events; catches and logs a listener's `RuntimeException` |
| `BoundedHttp` | aegis-mcp | Outbound HTTP with a total deadline and a reply size bound |
| `GatewayFactory`, `Gateway` | aegis-config | Read `tracing.otlp`, build and register the exporter, close it; `Gateway.tracer()` returns it |

## Failure behaviour

- Collector down or refusing connections: `send` catches the exception, adds one to
  `failedBatches()` and logs only the exception class at DEBUG. The request is not affected.
- Non-2xx reply, including a 3xx because redirects are not followed: one failed batch, the status is
  logged at DEBUG.
- Slow collector: each batch is bounded by `timeoutMs`. Meanwhile the queue fills and new spans are
  dropped and counted instead of waiting.
- An unexpected `RuntimeException` in the worker loop is counted as a failed batch, the batch is
  discarded, a WARNING is logged and the worker keeps running.
- A `RuntimeException` from any listener is caught by `Listeners.notify` (first one at WARNING, later
  ones at DEBUG); the decision is unchanged.
- Startup fails for an `endpoint` that is not a URI or not allowed by `egress`, or a `tokenEnv` that
  is not set. The constructor rejects a null endpoint with `IllegalArgumentException`.
- Dropped and failed spans are not retried or persisted; the audit journal remains the record.

## Tests

`OtlpSpanExporterTest` (this module), using a local `HttpServer` as the collector:

- `theDocumentHasTheShapeTheTraceServiceExpects`: `resourceSpans`, `service.name` and scope; hex ids
  and the parent span; name, kind, nanosecond timestamps as strings, end equal to start plus
  duration, UNSET status and every attribute.
- `aDenialBecomesAnErrorSpanCarryingTheStableDenyCode`: ERROR status with the deny code and step.
- `noCallerIdentityArgumentsOrResultsAreExported`: the whole JSON has no `actor`, `caller`,
  `subject`, `arguments`, `issuer`, `result`, `authorization` or `token`; a root span has no
  `parentSpanId`; a content span is named by its method and has no `aegis.tool`.
- `spansReachACollectorAndTheExporterDrainsOnClose`: headers arrive, and all five queued spans are
  exported on `close()` with no drops or failures.
- `aBrokenOrAbsentCollectorNeverAffectsTheRequestThread`: an HTTP 500 is a counted failed batch, and
  a closed port throws nothing.
- `aFullQueueDropsSpansAndCountsThemRatherThanBlocking`: with a queue of 1, `onDecision` keeps
  returning and drops are counted.
- `anEventWithoutSpanIdentityIsIgnoredAndAnEndpointIsRequired`: events without a span and null
  events are ignored; a null endpoint is rejected.

`GatewayFactoryTest.tracingOtlpIsWiredFromConfigurationAndExportsToARealCollector` (aegis-config)
covers the wiring end to end: a gateway built from `tracing.otlp` exports a governed decision to a
local collector with the configured service name and bearer token, no exporter exists without the
section, and startup is refused for an endpoint outside `egress`, an unset `tokenEnv`, or keys without
an `endpoint`. `KnoxPackagingBoundaryTest` (examples/aegis-examples) checks that the exporter class is
in the Knox bundle.

## Developer instructions

```powershell
mvn -B -pl aegis-observability-otlp -am test
```

1. Spans must not export caller identity, arguments, or results.
2. A full queue drops spans; never block the request path on the collector.
3. Endpoint must be egress-checked like every other outbound URL.

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-observability-otlp)
- [Configuration](../docs/CONFIGURATION.md)
- [AGENTS.md](../AGENTS.md)
