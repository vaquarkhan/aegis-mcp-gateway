<!--
  Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
  Licensed under the Apache License, Version 2.0
  https://www.apache.org/licenses/LICENSE-2.0
-->
# Configuration template

`TEMPLATE.yaml` is a teaching template, not a deployable product profile. It shows the minimum shape
for a new HTTP adopter: gateway name, policy, audit, egress, one source and one tool.

## Use it safely

Copy it into a new named adopter directory, then replace every placeholder with reviewed values:

```text
adopters/configurations/adapters/my-project/
  my-project.yaml
  README.md
```

Do not leave placeholder endpoints, roles, tools, resource types or signing-key environment names in a
deployment profile. Decide whether the backend has a real HTTP API; use a generic JDBC/ZooKeeper source
or a typed `ToolSourceFactory` when it does not.

## Evidence

`ExampleConfigsTest` fills the template placeholders only for a structural configuration test. That does
not certify any product integration.
