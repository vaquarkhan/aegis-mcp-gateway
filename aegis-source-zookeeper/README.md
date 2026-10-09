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
# aegis-source-zookeeper

Typed Apache ZooKeeper tool source (`type: zookeeper`). Path arguments are validated as typed
resources; the plugin must not treat them as free-form shell or script input.

## What it does

The module offers the ZooKeeper data tree as six MCP tools: list children, stat, get, create, set data
and delete. It talks to the ensemble through ZooKeeper's own Java client, not over HTTP. Every session
is authenticated with a digest credential, and every node it creates is owner-only. Writes and deletes
carry the version the caller expects, so ZooKeeper refuses a change to a node that moved. The engine
decides whether a call may run; this module only runs it.

## How it works end to end

1. **Discovery.** `src/main/resources/META-INF/services/io.github.vaquarkhan.aegis.config.spi.ToolSourceFactory`
   registers `ZooKeeperToolSourceFactory`, whose `type()` returns `zookeeper`. For a source with
   `type: zookeeper`, `GatewayFactory` finds the factory through `ServiceLoader` and calls
   `create(id, settings, BuildContext)`.
2. **Settings.** The factory reads the keys below. `GatewayFactory` calls `ConfigNode.finish()`, so any
   other key under `settings:` fails startup.

   | Key | Default | Rule |
   | --- | --- | --- |
   | `connectString` | required | The ensemble; configuration only, never an argument. |
   | `auth.digestEnv` | required | Name of an environment variable holding `user:password`, looked up through `BuildContext.env()`. Unset or blank fails startup. |
   | `sessionTimeoutMs` | `15000` | Passed to the `ZooKeeper` constructor. |
   | `connectTimeoutMs` | `10000` | How long a call waits for `SyncConnected`. |
   | `maxDataBytes` | `65536` | 1 to 1048576; cap on node data returned. |
   | `maxChildren` | `500` | 1 to 100000; cap on children listed. |

3. **Tools.** `ZooKeeperToolSource.discover()` always returns the same six tools. Every input schema
   sets `additionalProperties: false`; `path` is 1 to 1024 characters, `version` is an integer from -1
   to 2147483647, and `data` is at most 262144 characters. Class, resources and dry-run mode come from
   the source's `tools:` section in the operator's configuration.
4. **Engine first.** The engine runs its controls and calls `ToolExecutor.execute` only when they pass.
   `egressTargets` returns an empty list: the client opens its own socket, and the target is the
   configured `connectString`.
5. **Session.** No connection is made at startup. The first call runs
   `ZooKeeperToolSourceFactory.connect`, which creates the client, waits up to `connectTimeoutMs` for
   `SyncConnected`, then calls `addAuthInfo("digest", ...)`. The client is reused; when its state is no
   longer alive, the next call opens a new one. `close()` closes it.
6. **Arguments, without string concatenation.** Each tool calls one typed client method. `path` is
   checked by ZooKeeper's own `PathUtils.validatePath` before use, and `version` must be an integral value.
   - `zookeeper_list_children`: `getChildren(path, false)`.
   - `zookeeper_stat_node`: `exists(path, false)`.
   - `zookeeper_get_node`: `getData(path, false, stat)`.
   - `zookeeper_create_node`: `create(path, data, ZooDefs.Ids.CREATOR_ALL_ACL, CreateMode.PERSISTENT)`.
   - `zookeeper_set_data`: `setData(path, data, version)`.
   - `zookeeper_delete_node`: `delete(path, version)`.

   `data` is encoded as UTF-8 (empty when absent). ZooKeeper checks `version` itself and answers
   `BADVERSION` on a mismatch; `-1` skips the check. No watches are set.
7. **Results.** All results are structured JSON. `zookeeper_list_children` sorts names, keeps at most
   `maxChildren`, and returns `path`, `children`, `childCount`, `truncated` and a `note` when cut.
   `zookeeper_stat_node` returns `path`, `version`, `dataLength`, `numChildren`, `czxid`, `mzxid` and
   `ephemeralOwner`, and no data. `zookeeper_get_node` adds `dataBytes` (the real length),
   `dataTruncated`, `data` (at most `maxDataBytes` bytes) and `encoding`: `utf-8` when the bytes decode
   strictly as UTF-8, otherwise `base64`. Create returns `created` and `dataBytes`; set data returns the
   new stat plus `dataBytes`; delete returns `deleted` and `version`.

Minimal source, adapted from
[adopters/configurations/adapters/zookeeper/zookeeper.yaml](../adopters/configurations/adapters/zookeeper/zookeeper.yaml)
(run against a real ZooKeeper server by `ZooKeeperFrontDoorsTest`):

```yaml
policy:
  rules:
    - {id: read, groups: [zookeeper-users, zookeeper-operators], classes: [READ]}
    - {id: operate, groups: [zookeeper-operators], classes: [MUTATE, DESTRUCTIVE]}
    - id: never-write-reserved-znodes
      effect: DENY
      groups: ["*"]
      tools: [zookeeper_create_node, zookeeper_set_data, zookeeper_delete_node]
      resources: ["zookeeper:znode:/zookeeper*"]

sources:
  - id: zookeeper
    type: zookeeper
    settings:
      connectString: zookeeper:2181
      sessionTimeoutMs: 15000
      connectTimeoutMs: 10000
      auth: {digestEnv: AEGIS_ZOOKEEPER_DIGEST}
      maxDataBytes: 65536
      maxChildren: 500
    tools:
      zookeeper_list_children: {class: READ, resources: [{type: "zookeeper:znode", pointer: /path}]}
      zookeeper_stat_node: {class: READ, resources: [{type: "zookeeper:znode", pointer: /path}]}
      zookeeper_get_node:
        class: READ
        allowedGroups: [zookeeper-operators]
        resources: [{type: "zookeeper:znode", pointer: /path}]
      zookeeper_create_node: {class: MUTATE, resources: [{type: "zookeeper:znode", pointer: /path}]}
      zookeeper_set_data: {class: MUTATE, resources: [{type: "zookeeper:znode", pointer: /path}]}
      zookeeper_delete_node:
        class: DESTRUCTIVE
        resources: [{type: "zookeeper:znode", pointer: /path}]
        dryRun: {mode: TOOL, previewTool: zookeeper_stat_node}
```

```text
MCP call -> engine controls -> allowed
  -> ZooKeeperToolSource executor
  -> client(): reuse a live session, or connect():
       new ZooKeeper(connectString, sessionTimeoutMs) -> wait SyncConnected (connectTimeoutMs)
       -> addAuthInfo("digest", user:password)
  -> PathUtils.validatePath(path) -> refused? tool error "Invalid path: ..."
  -> getChildren | exists | getData | create(CREATOR_ALL_ACL) | setData(version) | delete(version)
  -> cap children at maxChildren, data at maxDataBytes (utf-8 or base64)
  -> ToolResult (structured JSON) -> engine -> MCP client
```

## Tools

| Tool | Class | What it does |
| --- | --- | --- |
| `zookeeper_list_children` | `ZooKeeperToolSource` | Sorted child names of `path`, capped at `maxChildren`. |
| `zookeeper_stat_node` | `ZooKeeperToolSource` | Version, data length, child count and zxids of `path`; no data. |
| `zookeeper_get_node` | `ZooKeeperToolSource` | Data and stat of `path`, data capped at `maxDataBytes`. |
| `zookeeper_create_node` | `ZooKeeperToolSource` | Creates one persistent znode with `CREATOR_ALL_ACL` and optional `data`. |
| `zookeeper_set_data` | `ZooKeeperToolSource` | Replaces the data of `path` if `version` matches (`-1` for any). |
| `zookeeper_delete_node` | `ZooKeeperToolSource` | Deletes `path` if it has no children and `version` matches (`-1` for any). |

## Failure behaviour

- No session (the client cannot be created, or `SyncConnected` does not arrive within
  `connectTimeoutMs`) raises `ExecutionException` "ZooKeeper is unavailable: ..." marked as an
  availability failure. The message does not contain the connect string.
- `CONNECTIONLOSS`, `SESSIONEXPIRED`, `SESSIONMOVED` and `OPERATIONTIMEOUT` raise `ExecutionException`
  and count toward the circuit breaker; `OPERATIONTIMEOUT` is also flagged as a timeout.
- Any other `KeeperException` code (for example `NONODE`, `NODEEXISTS`, `BADVERSION`, `NOTEMPTY`,
  `NOAUTH`) is a tool error "ZooKeeper refused the request: CODE for PATH" and does not count against
  the breaker.
- An `IllegalArgumentException` (a path rejected by `PathUtils`, or a missing `version`) is a tool
  error starting with `Invalid path:`.
- `zookeeper_stat_node` on a missing node is a tool error "No such znode: PATH".
- An interrupt raises `ExecutionException` "interrupted" and restores the interrupt flag. Any other
  exception raises `ExecutionException` naming only the exception class.
- At startup, a missing `connectString` or `auth.digestEnv`, an unset variable, or an out-of-range
  `maxDataBytes` or `maxChildren` is a `ConfigException`.

## Limits

- There is no unauthenticated path: `connect` refuses a null auth scheme or value, and the factory
  always passes `digest`.
- The executors do not read `ExecutionContext.timeoutMillis()`; the only timeouts this module sets
  are `connectTimeoutMs` and `sessionTimeoutMs`.
- The egress allow list does not apply to this source; `egressTargets` is always empty.
- The reserved `/zookeeper` subtree is not protected in code. Protect it with a policy rule, as above.
- Only persistent nodes are created; there are no ephemeral, sequential or recursive operations.
- `maxDataBytes` caps what is returned, not what is written.

## Tests

- `src/test/java/io/github/vaquarkhan/aegis/source/zookeeper/ZooKeeperToolSourceTest.java` runs against
  a real ZooKeeper server started in-process by `curator-test` (`TestingServer`). It covers an
  unreachable ensemble as an availability failure without the connect string in the message, the
  tool list, sorted and capped children, stat without data, UTF-8 and Base64 data, data truncation,
  `CREATOR_ALL_ACL` with a `digest` ACL on created nodes, `NODEEXISTS`, `BADVERSION`, `NOTEMPTY` and
  `NONODE` as caller errors, versioned writes and deletes, and `PathUtils` rejecting bad paths.
- `examples/aegis-validation/.../ZooKeeperFrontDoorsTest.java` (profile `real-engines`) runs
  `zookeeper.yaml` against ZooKeeper 3.9.3 through both front doors, including the governed
  `zookeeper_delete_node` flow.

## Developer instructions

```powershell
mvn -B -pl aegis-source-zookeeper -am test
```

1. Keep path validation strict (no `..`, wildcards, or control characters from callers).
2. Destructive tools need approval + dry-run receipt like every other DESTRUCTIVE tool.
3. Real two-door evidence: [`examples/adopters/zookeeper`](../examples/adopters/zookeeper/).

## Further reading

- [Module reference](../docs/guide/modules.html#aegis-source-zookeeper)
- [ZooKeeper tutorial](../docs/tutorials/zookeeper.md)
- [AGENTS.md](../AGENTS.md)
