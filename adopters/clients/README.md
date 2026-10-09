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
# Any language, no SDK: clients in Python, Node and Rust

A fair question about a governance gateway written in Java is whether it only governs Java agents. It
does not, and this directory is the demonstration rather than the assertion.

Aegis speaks MCP. In the **2026-07-28** revision there is no handshake and no session: every request is
a JSON-RPC document in an HTTP POST that carries its own protocol version. So an MCP client is anything
that can POST JSON, and the governance decision is made entirely by the gateway. None of the three
clients here uses an MCP SDK, an HTTP library, or any Aegis code:

| Client | Dependencies | Lines |
| --- | --- | --- |
| [`python/aegis_client.py`](python/aegis_client.py) | Python standard library (`urllib`, `json`) | ~110 |
| [`node/aegis-client.mjs`](node/aegis-client.mjs) | Node built-in `fetch`, no `npm install` | ~80 |
| [`rust/aegis_client.rs`](rust/aegis_client.rs) | Rust standard library only: **no crates**, hand-written HTTP over `TcpStream`, no JSON parser | ~170 |

The Rust one is deliberately the hard way round. If a governed call can be made with a socket and a
string, the protocol surface really is that small.

## The request shape

Three headers and a body. The protocol version travels in both the header and `params._meta`, and the
gateway rejects a request where they disagree, so a proxy cannot rewrite one without the other.

```http
POST /mcp HTTP/1.1
Authorization: Bearer <token>
Content-Type: application/json
MCP-Protocol-Version: 2026-07-28
Mcp-Method: tools/call
Mcp-Name: demo_delete_thing

{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"demo_delete_thing","arguments":{"id":"t-1"},
           "_meta":{"io.modelcontextprotocol/protocolVersion":"2026-07-28"}}}
```

A 2025-era client instead calls `initialize` first and echoes the `Mcp-Session-Id` the server returns.
Aegis serves both eras; the modern one is shown here because it is the one with nothing to remember.

## Run it

```bash
mvn -B clean install          # once, from the repository root
pwsh ./run-demo.ps1           # starts the example service and gateway, runs every client, stops them
```

The demo governs the functional local
[`example_inventory_service.py`](example_inventory_service.py), whose list and delete operations
change its in-process inventory. It is intentionally not presented as a real cluster or target-product
compatibility test: the point is the client boundary. [`aegis-clients-demo.yaml`](aegis-clients-demo.yaml)
exposes two tools: `demo_list_things` (`READ`) and `demo_delete_thing` (`DESTRUCTIVE`).

## Recorded output

Captured from an actual run, not written by hand (environment: [Tested environments](../../docs/VALIDATION.md#tested-environments)):

```text
== python 3.13.2, standard library only, MCP 2026-07-28, no handshake and no session ==
tools/list as analyst   -> ['demo_list_things']
                           the destructive tool is not even advertised to a reader
READ as analyst         -> [{"id":"t-1","name":"orders"},{"id":"t-2","name":"payments"}]
DESTRUCTIVE as analyst  -> refused READONLY_CALLER at step 1
DESTRUCTIVE as operator -> refused APPROVAL_REQUIRED at step 4
dry run as operator     -> allowed, receipt=True approvalRequest=True
undeclared argument     -> refused INVALID_INPUT at step 2
python client done

== node v24.14.0, built-in fetch only, MCP 2026-07-28, no handshake and no session ==
tools/list as analyst   -> [ 'demo_list_things' ]
READ as analyst         -> [{"id":"t-1","name":"orders"},{"id":"t-2","name":"payments"}]
DESTRUCTIVE as analyst  -> refused READONLY_CALLER at step 1
DESTRUCTIVE as operator -> refused APPROVAL_REQUIRED at step 4
dry run as operator     -> allowed, receipt=true approvalRequest=true
undeclared argument     -> refused INVALID_INPUT at step 2
node client done
```

And the question that matters, asked of the backend rather than of the gateway. After both clients ran,
every attempt included, this is the complete list of requests the backend saw:

```json
["GET /things", "GET /things"]
```

Two reads, one per client. **Zero deletes.** Every destructive attempt, in both languages, was refused
before anything was sent to the engine. Identical deny codes and identical step numbers from both
clients, because neither client decides anything.

## Rust: compiled, not linked

The Rust client **compiles and type-checks** with the command below (environment: [Tested environments](../../docs/VALIDATION.md#tested-environments)):

```console
$ rustc --emit=metadata --edition 2021 -o aegis_client.rmeta aegis_client.rs
$ echo $?
0
```

It could **not** be linked into an executable here, because neither an MSVC nor a MinGW linker is
installed:

```console
$ rustc -O aegis_client.rs -o aegis_client.exe
error: linker `link.exe` not found
$ rustc -O --target x86_64-pc-windows-gnu aegis_client.rs -o aegis_client.exe
error: linker `x86_64-w64-mingw32-gcc` not found
```

So its runtime output is **not** in the transcript above, and this page does not claim it ran. On a
machine with a linker, `run-demo.ps1` executes it and prints the same lines; that is an expectation, not
a recorded result. Python and Node are the two that actually ran.

## Agent frameworks

The same reasoning covers the frameworks people build agents with, including
[Apache Burr](https://burr.apache.org/) (incubating), a pure-Python library for building agents as
state machines. Burr is the orchestrator, not the transport: a Burr action that calls a tool makes an
HTTP request, which is exactly what `python/aegis_client.py` does. So Aegis needs no Burr plugin and
Burr needs no Aegis library; point the action at the gateway URL and the call is governed.

To be precise about what has and has not been tried: the Python client above ran, and Burr specifically
was **not** tested here. The claim is about the protocol boundary, which is the same one either way.

## Why this matters beyond a compatibility checkbox

Governance that depends on the client is not governance. If the rules lived in a Python SDK, then a
Node agent, a Rust agent, or a curl command would bypass them, and "the agent must use our library"
becomes the weakest link in the chain. Because Aegis enforces at the protocol boundary:

- there is no client library to keep in step across languages;
- an agent cannot opt out of the controls by choosing a different language or writing raw HTTP;
- the audit journal has one shape regardless of who called;
- adding a language costs nothing, as this directory shows.
