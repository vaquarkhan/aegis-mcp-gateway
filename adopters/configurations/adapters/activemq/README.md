<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Apache ActiveMQ adopter

`activemq.yaml` governs three ActiveMQ Classic 6.x Jolokia operations: broker status, queue discovery and
destructive queue removal.

## Why this source

ActiveMQ exposes these operations through the Jolokia bridge on its web console, so this is a declarative
`http` profile. ActiveMQ 6 ships Jolokia as POST only, with an Origin check and basic auth, so every tool
is a POST of a Jolokia JSON request and the gateway holds the broker credential
(`ACTIVEMQ_JOLOKIA_USER`, `ACTIVEMQ_JOLOKIA_PASSWORD`). The Broker MBean name is fixed by the operator;
callers supply only the typed `activemq:queue` resource.

The 0.1 `purge_queue` is not kept: purge lives on the queue's own MBean, and naming that MBean from an
argument would mean splicing the argument into a string. `activemq_remove_queue` (Broker MBean
`removeQueue`) takes the queue as a separate argument instead.

## Broker change the profile needs

The shipped `conf/jolokia-access.xml` refuses every `exec`. Add `<command>exec</command>` and remove
`<operation>removeQueue</operation>` from the Broker MBean deny entry. Keep the rest denied.

## Run it

`activemq.yaml` in this folder is the profile. Its address is a service hostname. That name does not resolve on a laptop, and this file has no listener. Copy the kept own-host file, then change the address and the port.

1. From the repository root, build: `mvn -B -Preal-engines -DskipTests install`.
2. Start the engine with section 1 of [`examples/adopters/activemq/HOW-TO-RUN.md`](../../../../examples/adopters/activemq/HOW-TO-RUN.md). The host port in that command is the free port from the kept run. Pick your own host port and use it in the configuration below. The same scenario is written out in [`docs/tutorials/activemq.md`](../../../../docs/tutorials/activemq.md).
3. Copy [`examples/adopters/activemq/aegis/own-host.yaml`](../../../../examples/adopters/activemq/aegis/own-host.yaml) to a file you do not commit. In that copy set `host.listen.port` to `8080` (the kept file says `port: 0`) and set the engine address to the port you published.
4. In that terminal set `AEGIS_SIGNING_KEY` (at least 32 bytes) and these tokens (each at least 16 characters): `AEGIS_VALIDATION_TOKEN_READER` (`guest`), `AEGIS_VALIDATION_TOKEN_OPERATOR` (`sam`), `AEGIS_VALIDATION_TOKEN_APPROVER` (`tom`), `AEGIS_VALIDATION_TOKEN_OUTSIDER` (`admin`).

Also set `ACTIVEMQ_JOLOKIA_USER` and `ACTIVEMQ_JOLOKIA_PASSWORD`.
5. Start Aegis from the repository root. This process stays up.

```powershell
java -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar `
  --config <path to your copy of own-host.yaml>
```

On bash the same jar command uses `--config` the same way.

The log line `Aegis gateway listening on http://127.0.0.1:8080/mcp` is the URL the editor calls. `examples/adopters/run-example.ps1 activemq -Keep` re-checks both doors and then stops them. `-Keep` overwrites the retained evidence. It is not the way to leave a gateway up.

6. Copy one IDE file into the project the agent opens:

| Editor | Copy | To |
| --- | --- | --- |
| Cursor | [`adopters/tutorials/activemq/ide/cursor/mcp.json`](../../../tutorials/activemq/ide/cursor/mcp.json) | `.cursor/mcp.json` |
| VS Code | [`adopters/tutorials/activemq/ide/vscode/mcp.json`](../../../tutorials/activemq/ide/vscode/mcp.json) | `.vscode/mcp.json` |
| VS Code through Knox | [`adopters/tutorials/activemq/ide/vscode/mcp.knox.json`](../../../tutorials/activemq/ide/vscode/mcp.knox.json) | `.vscode/mcp.json` |
| Kiro | [`adopters/tutorials/activemq/ide/kiro/mcp.json`](../../../tutorials/activemq/ide/kiro/mcp.json) | `.kiro/settings/mcp.json` |

Replace `REPLACE_WITH_YOUR_TOKEN` with the reader token when the assistant should act as `guest`. Reload MCP. You are ready when the gateway log still says `Aegis gateway listening on http://127.0.0.1:8080/mcp` and the editor lists the server from that JSON with its tools. Then name that server in the agent chat. The agent calls Aegis, not the engine URL.

7. Knox door, after `examples/adopters/knox/install-knox.ps1` or `install-knox.sh` and `AEGIS_KNOX_HOME`:

| Copy | To |
| --- | --- |
| `aegis-knox3-extension/target/aegis-knox3-extension-2.0.0-SNAPSHOT-knox-bundle.jar` | `$AEGIS_KNOX_HOME/ext/` |
| [`examples/adopters/activemq/aegis/knox-aegis.yaml`](../../../../examples/adopters/activemq/aegis/knox-aegis.yaml) | `$AEGIS_KNOX_HOME/conf/aegis/activemq.yaml` |
| [`examples/adopters/activemq/aegis/knox-topology.xml`](../../../../examples/adopters/activemq/aegis/knox-topology.xml) | `$AEGIS_KNOX_HOME/conf/topologies/aegis-activemq.xml` |

Replace `${KNOX_HOME}` in the topology copy. In `$AEGIS_KNOX_HOME/conf/gateway-site.xml` set `gateway.port` to `8443` and `gateway.host` to `127.0.0.1`. From the Knox directory run `java -jar bin/knoxcli.jar create-master`, `java -jar bin/knoxcli.jar create-cert --hostname localhost`, `java -jar bin/ldap.jar conf`, and `java -jar bin/gateway.jar`.

## Evidence

Both front doors passed. The Surefire report, transcripts, and the files the test loaded are in [`examples/adopters/activemq/`](../../../../examples/adopters/activemq/).
