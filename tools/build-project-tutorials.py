#!/usr/bin/env python3
# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Builds one IDE-ready tutorial per verified project from what its kept real run recorded.

Inputs, per project, all under examples/adopters/<project>/ and written by the two-door harness
(examples/adopters/run-example.ps1 <project> -Keep):
  HOW-TO-RUN.md                 engine command lines, start-up steps and every request with its result
  aegis/own-host.yaml           the Aegis configuration the own-host door loaded
  aegis/knox-topology.xml       the Knox topology the Knox door used
  results/test-report.txt       the Surefire summary of that run
  results/own-host-transcript.log, results/knox-transcript.log

Outputs:
  docs/tutorials/<project>.md
  docs/tutorials/README.md      the index

The generator synthesizes shared build, runner, endpoint, IDE, audit, and cleanup guidance. Retained
run files provide scenario evidence for the recorded tools, personas, outcomes, test count, and date. A
project whose kept run is missing a file, or whose report shows a failure or skip, is not written and
is reported instead.

Usage: python tools/build-project-tutorials.py [--check]
  --check   exit 1 if any output would change (for CI), writing nothing
"""
import json
import re
import sys
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
EXAMPLES = ROOT / "examples" / "adopters"
DOCS = ROOT / "docs" / "tutorials"
IDE_ROOT = ROOT / "adopters" / "tutorials"

HEADER_MD = """<!--
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
"""

# Display names and the hand-written deep-dive tutorial, where one exists. Everything else is derived.
PROJECTS = {
    "activemq": ("Apache ActiveMQ", None),
    "airflow": ("Apache Airflow", None),
    "bookkeeper": ("Apache BookKeeper", None),
    "burr": ("Apache Burr (incubating)", None),
    "calcite": ("Apache Calcite (Avatica)", None),
    "couchdb": ("Apache CouchDB", None),
    "doris": ("Apache Doris", None),
    "druid": ("Apache Druid", None),
    "existing-mcp-server": ("an existing MCP server", None),
    "flink": ("Apache Flink", "../TUTORIAL-FLINK.md"),
    "flume": ("Apache Flume", None),
    "hadoop": ("Apache Hadoop HDFS", None),
    "hbase": ("Apache HBase", None),
    "iceberg": ("Apache Iceberg REST catalog", "../TUTORIAL-ICEBERG.md"),
    "ignite": ("Apache Ignite", None),
    "kafka": ("Apache Kafka and Kafka Connect", "../TUTORIAL-KAFKA-AND-KAFKA-CONNECT.md"),
    "nifi": ("Apache NiFi", None),
    "pinot": ("Apache Pinot", None),
    "pulsar": ("Apache Pulsar", None),
    "solr": ("Apache Solr", None),
    "spark": ("Apache Spark", "../TUTORIAL-SPARK-AND-SPARK-CONNECT.md"),
    "spark-connect": ("Apache Spark Connect", "../TUTORIAL-SPARK-AND-SPARK-CONNECT.md"),
    "storm": ("Apache Storm", None),
    "superset": ("Apache Superset", None),
    "zookeeper": ("Apache ZooKeeper", None),
}
# Spark with Iceberg has its own hand-built tutorial, scripts and IDE files (docs/TUTORIAL-SPARK-ICEBERG.md).

# These projects have retained PowerShell runner cases but no run-example.sh case.
POWERSHELL_ONLY_RUNNER = {"airflow", "doris", "druid", "hadoop", "hbase", "nifi", "storm", "superset"}

# A real-engines build writes these portable classpath files. Their entries replace machine-specific
# jar lists retained in HOW-TO-RUN.md.
PLUGIN_CLASSPATH_FILES = {
    "calcite": "aegis-source-jdbc/target/plugin.classpath",
    "kafka": "examples/aegis-examples/target/plugin.classpath",
    "spark-connect": "examples/aegis-spark-connect/target/plugin.classpath",
    "zookeeper": "aegis-source-zookeeper/target/plugin.classpath",
}

VERBS = {"read", "write", "list", "get", "put", "delete", "create", "describe", "drop", "stop", "start", "cancel",
          "pause", "resume", "restart", "trigger", "run", "submit", "purge", "send", "query", "search", "check",
          "explain", "execute", "edit", "move", "upload"}

OWN_HOST_PORT = 8080
KNOX_PORT = 8443

ROW = re.compile(r"^\| (\d+) \| (.+?) \| `(.+?)` \((\w+)\) \| `(.+?)` \| (.+?) \|$")


def section(text, heading_prefix):
    """Body of the '## <n>. ...' section whose heading starts with heading_prefix."""
    lines = text.splitlines()
    out, inside = [], False
    for line in lines:
        if line.startswith("## "):
            if inside:
                break
            inside = line.startswith("## " + heading_prefix)
            continue
        if inside:
            out.append(line)
    return "\n".join(out).strip()


def request_rows(body):
    rows = []
    for line in body.splitlines():
        m = ROW.match(line)
        if m:
            rows.append({"n": int(m.group(1)), "step": m.group(2), "caller": m.group(3), "role": m.group(4),
                         "request": m.group(5), "result": m.group(6)})
    return rows


def report_counts(text):
    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", text)
    return tuple(int(x) for x in m.groups()) if m else None


def source_types(cfg):
    return {src.get("id", "?"): src.get("type", "?") for src in cfg.get("sources", []) or []}


def missing_description(source_type):
    """Factual text for a tool whose description is not in own-host.yaml (the kept run has no tools/list)."""
    if source_type == "mcp":
        return "Description comes from the backend's `tools/list`; the kept run does not record it."
    if source_type == "http":
        return "No description in `own-host.yaml`."
    if source_type == "?":
        return "Description comes from the source; the kept run does not record it."
    return f"Description comes from the `{source_type}` source plugin; the kept run does not record it."


def tools_from_config(cfg):
    tools = {}
    for src in cfg.get("sources", []) or []:
        for t in src.get("tools", []) or []:
            if isinstance(t, dict) and t.get("name"):
                tools[t["name"]] = {"class": t.get("class", "DESTRUCTIVE (unclassified)"),
                                    "description": " ".join(str(t.get("description", "")).split()),
                                    "source": src.get("id", "?")}
    return tools


BACKEND_TOOL = re.compile(r'@\w+\.tool\(description="([^"\\]+)"\)\s*\n\s*(?:async\s+)?def (\w+)\(')


def backend_descriptions(project, base, cfg, tools):
    """Fill descriptions of `mcp` source tools from a Python MCP backend kept with the run, if there is one.

    The kept run has no tools/list, but a backend kept in examples/adopters/<project>/ declares each tool's
    description in its own source (`@server.tool(description=...)`). The source's `namePrefix` maps the
    backend's tool name to the name Aegis exposes.
    """
    declared = {}
    for py in sorted(base.glob("*.py")):
        for desc, fn in BACKEND_TOOL.findall(py.read_text(encoding="utf-8")):
            declared.setdefault(fn, (desc, py.name))
    if not declared:
        return
    prefixes = {src.get("id", "?"): src.get("namePrefix", "") or ""
                for src in cfg.get("sources", []) or [] if src.get("type") == "mcp"}
    for name, t in tools.items():
        prefix = prefixes.get(t["source"])
        if t["description"] or prefix is None or not name.startswith(prefix):
            continue
        hit = declared.get(name[len(prefix):])
        if hit:
            t["description"] = (f"{hit[0]} (declared by the backend in "
                                f"[`{hit[1]}`](../../examples/adopters/{project}/{hit[1]}))")


SHELL_FENCES_WITH_SEMICOLON = {"powershell", "pwsh", "ps1", "cmd", "bat"}
CP_FLAG = r"(?:-cp|-classpath|--class-path)"


def unix_classpaths(text):
    """Join Java classpath entries with ':' in every fenced block that is not PowerShell or cmd.

    The kept runs were recorded on Windows, whose separator is ';'. Bash-style blocks (bash, sh and the
    `text` command lines written with `\\` continuations) need ':'; PowerShell blocks keep ';'.
    """
    out, fence, next_is_cp = [], None, False
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("```"):
            fence = None if fence is not None else (stripped[3:].strip().lower() or "text")
            next_is_cp = False
        elif fence is not None and fence not in SHELL_FENCES_WITH_SEMICOLON:
            if next_is_cp:
                line = line.replace(";", ":")
            else:
                line = re.sub(r"(\s" + CP_FLAG + r"\s+)(\"[^\"]*\"|'[^']*'|[^\s\"'\\]+)",
                              lambda m: m.group(1) + m.group(2).replace(";", ":"), line)
            next_is_cp = re.fullmatch(CP_FLAG + r"\s*\\", stripped) is not None
        out.append(line)
    return "\n".join(out)


SECRET_NAME = r"\w*(?:TOKEN|PASSWORD|DIGEST|SECRET)\w*"
TEST_SECRET_NOTE = "test value used by the kept run; use your own secret"


def label_test_secrets(text):
    """Mark secret values copied from the kept run as test-only, without changing the values."""
    out, fence = [], None
    for line in text.split("\n"):
        stripped = line.strip()
        if stripped.startswith("```"):
            fence = None if fence is not None else (stripped[3:].strip().lower() or "text")
        elif TEST_SECRET_NOTE in line:
            pass
        elif fence is None:
            # Environment bullets: - `NAME`: value `x`  or  - `NAME` = `x`
            if re.fullmatch(r"- `" + SECRET_NAME + r"`(?:: value| =) `[^`]+`", stripped):
                line += f" ({TEST_SECRET_NOTE})"
        elif re.match(r"\s*(?:export\s+|\$env:)?" + SECRET_NAME + r"\s*[:=]\s*['\"]?[^\s$<'\"]", line):
            # Do not append a comment after a continuation backslash because it changes the command.
            if not line.rstrip().endswith("\\"):
                line += f"  # {TEST_SECRET_NOTE}"
        out.append(line)
    return "\n".join(out)


def groups(cfg):
    access = cfg.get("access", {}) or {}
    return {k: access.get(k, []) for k in ("writeGroups", "destructiveGroups", "approverGroups")}


def strip_plugin_jar_list(body, how):
    # The retained jar list is machine-specific. The generated command uses the build's plugin.classpath.
    return re.sub(r"Plugin jars this run used:\n\n```text\n.*?```\n", lambda m: "The retained plugin jar list is in "
                  "the kept [HOW-TO-RUN.md](" + how + "), section 2. The command above reads the "
                  "portable `target/plugin.classpath` produced by the real-engines build.\n", body, flags=re.S)


def normalize_bash_block(body, project):
    """Turn retained Bash placeholders into runnable variables and secure Knox curl input."""
    body = body.replace("http://127.0.0.1:<port>", "http://127.0.0.1:${AEGIS_PORT}")
    body = body.replace("https://localhost:<gateway.port>", "https://localhost:${KNOX_PORT}")
    body = body.replace("ext/aegis-plugin-<jar>", "ext/aegis-plugin-JAR_NAME")
    body = re.sub(r"java -jar bin/knoxcli\.jar create-master --master <[^>]+>",
                  "java -jar bin/knoxcli.jar create-master", body)
    body = re.sub(r"^# -k because.*\n", "", body, flags=re.M)
    body = re.sub(r"curl -s?k\s+", 'curl -sS --cacert "$KNOX_CA_CERT" ', body)
    body = body.replace("-u <knox-demo-user>:<knox-demo-password>", '--user "$KNOX_USER"')
    if "<plugin jars>" in body:
        cp_file = PLUGIN_CLASSPATH_FILES[project]
        body = body.replace("<plugin jars>", "${AEGIS_PLUGIN_CLASSPATH}")
        body = f'AEGIS_PLUGIN_CLASSPATH="$(cat "$REPO/{cp_file}")"\n' + body

    prefix = []
    if "${AEGIS_PORT}" in body:
        prefix.append(f"AEGIS_PORT={OWN_HOST_PORT}")
    if "${KNOX_PORT}" in body:
        prefix.append(f"KNOX_PORT={KNOX_PORT}")
    if "$KNOX_CA_CERT" in body:
        prefix.extend([
            'java -jar "$AEGIS_KNOX_HOME/bin/knoxcli.jar" export-cert --type PEM',
            'KNOX_CA_CERT="$PWD/gateway-client-trust.pem"',
        ])
    if "$KNOX_USER" in body:
        prefix.extend([
            "read -r -p 'Knox demo user: ' KNOX_USER",
            "# curl prompts for the password because the user value contains no colon.",
        ])
    if prefix:
        body = "\n".join(prefix) + "\n" + body
    return body


def portable_commands(text, project):
    """Normalize retained command blocks without changing retained evidence files."""
    text = re.sub(r"<the jars listed in ([^>]+)>", r'"$(cat "\1")"', text)
    if project == "pulsar":
        text = re.sub(r"\$HOME/Downloads/kafka-trunk/\.aegis-real-engines/apache-pulsar-[A-Za-z0-9.+_-]+",
                      "$AEGIS_PULSAR_HOME", text)
    if project == "spark-connect":
        text = text.replace("file:///$REPO/examples/aegis-validation-spark-connect/target/"
                            "spark-connect-server/warehouse/",
                            '"$REPO/examples/aegis-validation-spark-connect/target/'
                            'spark-connect-server/warehouse"')
    if project == "hbase":
        text = text.replace("dajobe/hbase:latest",
                            '"${HBASE_IMAGE:?Set HBASE_IMAGE to an immutable dajobe/hbase digest}"')

    lines, out, language, block = text.split("\n"), [], None, []
    for line in lines:
        stripped = line.strip()
        if language is None and stripped.startswith("```"):
            language = stripped[3:].strip().lower()
            out.append(line)
        elif language is not None and stripped == "```":
            body = "\n".join(block)
            if language in {"bash", "sh"}:
                body = normalize_bash_block(body, project)
            out.extend(body.split("\n"))
            out.append(line)
            language, block = None, []
        elif language is not None:
            block.append(line)
        else:
            out.append(line)
    return "\n".join(out)


def docker_names(engine):
    return re.findall(r"(?m)^\s*--name\s+\\\n\s+([^\s\\]+)", engine)


def tutorial(project, title, deep):
    base = EXAMPLES / project
    how_path, cfg_path = base / "HOW-TO-RUN.md", base / "aegis" / "own-host.yaml"
    report_path = base / "results" / "test-report.txt"
    for p in (how_path, cfg_path, report_path, base / "aegis" / "knox-topology.xml"):
        if not p.is_file():
            return None, f"{project}: missing {p.relative_to(ROOT)}"
    how = how_path.read_text(encoding="utf-8")
    counts = report_counts(report_path.read_text(encoding="utf-8", errors="replace"))
    if counts is None or counts[1] or counts[2] or counts[3] or counts[0] < 2:
        return None, f"{project}: kept report is not a clean two-door pass: {counts}"
    run = re.search(r"during the run of `(\w+)` on (\d{4}-\d{2}-\d{2})", how)
    if not run:
        return None, f"{project}: HOW-TO-RUN.md does not name its test and date"
    test, date = run.group(1), run.group(2)
    cfg = yaml.safe_load(cfg_path.read_text(encoding="utf-8"))
    tools = tools_from_config(cfg)
    types = source_types(cfg)
    # Plugin and MCP sources describe their tools themselves; the kept journal records class and source only.
    audit = base / "results" / "own-host-audit.jsonl"
    if audit.is_file():
        for line in audit.read_text(encoding="utf-8").splitlines():
            rec = json.loads(line)
            if rec.get("tool") and rec.get("toolClass") and rec["tool"] not in tools:
                tools[rec["tool"]] = {"class": rec["toolClass"], "description": "", "source": rec.get("source", "?")}
    backend_descriptions(project, base, cfg, tools)
    recorded_classes = {str(t["class"]).split()[0] for t in tools.values()}
    own = section(how, "2.")
    knox = section(how, "3.")
    engine = section(how, "1.")
    own_rows, knox_rows = request_rows(own), request_rows(knox)
    if not own_rows or not knox_rows:
        return None, f"{project}: HOW-TO-RUN.md has no request table for both doors"
    route = re.search(r"The route: `https://localhost:<gateway\.port>(/gateway/[^`]+/mcp)`", knox)
    if not route:
        return None, f"{project}: no Knox route in HOW-TO-RUN.md"
    knox_path = route.group(1)
    topology = knox_path.split("/")[2]
    server = cfg.get("gateway", {}).get("name") or f"aegis-{project}"
    people = {}
    for r in own_rows + knox_rows:
        people.setdefault(r["caller"], r["role"])
    called = {}
    for r in own_rows:
        m = re.match(r"tools/call (\S+) (.*)$", r["request"])
        if m:
            called.setdefault(m.group(1), []).append(r)
    g = groups(cfg)
    rel_how = f"../../examples/adopters/{project}/HOW-TO-RUN.md"

    out = [HEADER_MD]
    out.append(f"# Tutorial: {title} with Aegis, from your IDE, with and without Knox\n")
    out.append(f"Built by `tools/build-project-tutorials.py` using retained evidence from the real run of `{test}` "
               f"on {date} ({counts[0]} tests, 0 failures, 0 skipped: the own-host door and the Knox 3.0.0 "
               f"door). The retained files in [`examples/adopters/{project}/`](../../examples/adopters/{project}/) "
               f"provide the scenario's tools, people, outcomes, test count, and date. The generator synthesizes "
               f"the shared build, runner, endpoint, IDE, audit, and cleanup commands; choose ports, paths, and "
               f"keys for your environment.")
    if deep:
        out.append(f"\nFor background on the product's own security model and a longer walkthrough, read "
                   f"[the deep-dive tutorial]({deep}).")
    out.append("\n## Contents\n")
    for i, h in enumerate(["What Aegis governs here", "Prerequisites and build", "Run the whole verified scenario",
                           "The engine", "Flavor A: without Knox", "Flavor B: with Knox", "Connect your IDE",
                           "Prompts to try", "What the run showed", "Audit and clean up"], 1):
        out.append(f"{i}. {h}")
    out.append("\n## 1. What Aegis governs here\n")
    governance = (f"The gateway exposes these tools (from [`aegis/own-host.yaml`](../../examples/adopters/"
                  f"{project}/aegis/own-host.yaml)). Aegis decides each call before the backend sees it. ")
    class_rules = []
    if "READ" in recorded_classes:
        class_rules.append("`READ` runs for callers allowed by policy")
    if "MUTATE" in recorded_classes:
        class_rules.append("`MUTATE` needs a write group and an independent per-call approval")
    if "DESTRUCTIVE" in recorded_classes:
        class_rules.append("`DESTRUCTIVE` needs a write group, an independent per-call approval, and a fresh "
                           "dry-run receipt bound to the same arguments")
    out.append(governance + "; ".join(class_rules) + ".\n")
    if tools:
        out.append("| Tool | Class | Source | What it does |")
        out.append("| --- | --- | --- | --- |")
        for name, t in sorted(tools.items()):
            out.append(f"| `{name}` | `{t['class']}` | `{t['source']}` | {t['description'] or missing_description(types.get(t['source'], '?'))} |")
    else:
        out.append("This profile's tools come from the backend at start-up (no static tool list in the file). "
                   "The tools the run called are in section 8.")
    out.append("")
    out.append("| Access setting | Groups |")
    out.append("| --- | --- |")
    for k, v in g.items():
        out.append(f"| `access.{k}` | {', '.join(f'`{x}`' for x in v) or '(none)'} |")
    out.append("\n| Person in the run | Role |")
    out.append("| --- | --- |")
    for p, role in people.items():
        out.append(f"| `{p}` | {role.lower()} |")

    out.append("\n## 2. Prerequisites and build\n")
    out.append("- JDK 17 or newer, Maven 3.9 or newer.")
    out.append("- For Flavor B, a stock Apache Knox 3.0.0 from `examples/adopters/knox/install-knox.ps1` (or `.sh`), "
               "which checks the release's SHA-512.")
    if re.search(r"(?m)^docker\s+\\", engine):
        out.append("- Docker with a running daemon and enough resources for the retained project image or images.")
    readme = (base / "README.md").read_text(encoding="utf-8") if (base / "README.md").is_file() else ""
    env_pattern = r"`(AEGIS_NODE|AEGIS_[A-Z_]+_(?:HOME|PYTHON|JS))`"
    envs = sorted(set(re.findall(env_pattern, readme + how)) - {"AEGIS_KNOX_HOME"})
    if envs:
        out.append("- This engine also needs: " + ", ".join(f"`{e}`" for e in envs)
                   + f" (see [the example README](../../examples/adopters/{project}/README.md)).")
    out.append("\n```bash\n# from the repository root\nmvn -B -Preal-engines -DskipTests install\n```")

    out.append("\n## 3. Run the whole verified scenario\n")
    out.append("One command starts the real engine, runs every step through both doors, and checks the engine's "
               "own state after each governed write. This is how the kept results were produced.\n")
    out.append(f"```powershell\n$env:AEGIS_KNOX_HOME = 'C:\\knox\\knox-3.0.0'\nexamples/adopters/run-example.ps1 {project} -Keep\n```\n")
    if project not in POWERSHELL_ONLY_RUNNER:
        out.append(f"```bash\nexport AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0\nexamples/adopters/run-example.sh {project} --keep\n```\n")
    else:
        out.append("This scenario has no `run-example.sh` case. Use the supported PowerShell runner above.")
    out.append("Without `AEGIS_KNOX_HOME` the Knox door is reported as skipped, never as passed.")

    out.append("\n## 4. The engine\n")
    out.append("In the commands below `$REPO` is the repository root, as in the retained HOW-TO-RUN.md.\n")
    if project == "hbase":
        out.append("The retained run used the mutable image tag `dajobe/hbase:latest`, with no version or digest "
                   "recorded. The command below deliberately requires `HBASE_IMAGE` to be an immutable "
                   "`dajobe/hbase@sha256:...` digest selected and recorded by the operator.\n")
    engine_body = engine.replace("](engine/", f"](../../examples/adopters/{project}/engine/")
    out.append(portable_commands(engine_body, project).strip())

    out.append("\n## 5. Flavor A: without Knox\n")
    own_body = strip_plugin_jar_list(own, rel_how)
    own_body = own_body.replace("](aegis/", f"](../../examples/adopters/{project}/aegis/")
    own_body = portable_commands(own_body, project)
    out.append(own_body.split("What the test sent through this door")[0].strip())
    out.append(f"\nFor an IDE you want a fixed port: in your copy of `own-host.yaml` set `host.listen.port: {OWN_HOST_PORT}`, "
               "and point the engine address at your engine. Nothing else changes.")

    out.append("\n## 6. Flavor B: with Knox\n")
    out.append("The manual commands in this section are POSIX shell commands. On Windows, use the supported "
               f"`examples/adopters/run-example.ps1 {project} -Keep` runner from section 3.")
    knox_body = knox.replace("](aegis/", f"](../../examples/adopters/{project}/aegis/")
    knox_body = portable_commands(knox_body, project)
    out.append(knox_body.split("What the test sent through this door")[0].strip())
    out.append("\nThe curl example exports Knox's certificate and supplies it with `--cacert`; do not replace this "
               "with `-k`. Verify the exported certificate through a trusted channel before using it. Curl "
               "prompts for the Knox password because `--user` contains only the user name, keeping the password "
               "out of shell history and process arguments.")
    out.append(f"\nFor an IDE, set `gateway.port` to {KNOX_PORT} in `conf/gateway-site.xml`.")

    out.append("\n## 7. Connect your IDE\n")
    out.append(f"Ready-made files, in each IDE's documented format, are in "
               f"[`adopters/tutorials/{project}/ide/`](../../adopters/tutorials/{project}/ide/):\n")
    out.append("| IDE | File | Door |")
    out.append("| --- | --- | --- |")
    out.append(f"| VS Code | `ide/vscode/mcp.json` (copy to `.vscode/mcp.json`) | own host, bearer token prompted |")
    out.append(f"| VS Code | `ide/vscode/mcp.knox.json` | Knox, HTTP Basic prompted |")
    out.append(f"| Kiro | `ide/kiro/mcp.json` (copy to `.kiro/settings/mcp.json`) | own host enabled, Knox disabled |")
    out.append(f"| Cursor | `ide/cursor/mcp.json` (copy to `.cursor/mcp.json`) | own host |")
    out.append(f"\nOwn host: `http://127.0.0.1:{OWN_HOST_PORT}/mcp`. Knox: `https://localhost:{KNOX_PORT}{knox_path}`; "
               "trust Knox's certificate first (`java -jar bin/knoxcli.jar export-cert --type PEM`). "
               "The token you give the IDE decides who you are to Aegis: use the token of the person whose "
               "permissions you want the assistant to have.")
    out.append("\nThese files were checked by `DocumentationAssetsTest` (they parse and name these endpoints). The "
               "MCP transport they use was exercised with the official MCP Python SDK in the "
               "[Spark and Iceberg tutorial](../TUTORIAL-SPARK-ICEBERG.md); the IDEs themselves were not driven "
               "by a test.")

    out.append("\n## 8. Prompts to try\n")
    out.append("Ask the assistant in plain language; it picks the tool. What Aegis answered for the same call in the "
               "kept own-host run is in the last column.\n")
    out.append("| Prompt | Tool | Class | What Aegis answered, by person |")
    out.append("| --- | --- | --- | --- |")
    for name, rows in called.items():
        args = json.loads(re.match(r"tools/call \S+ (.*)$", rows[0]["request"]).group(1) or "{}")
        parts = name.split("_")
        # Drop a product prefix (kafka_, engine_, zookeeper_ ...) but keep a leading verb (read_text_file).
        if len(parts) > 1 and parts[0] not in VERBS:
            parts = parts[1:]
        if parts[0] not in VERBS and parts[0] not in {"remove", "destroy", "kill", "annotate", "preview"}:
            parts = ["show"] + parts
        prompt = f"Use {server} to {' '.join(parts)}"
        if args:
            prompt += " with " + ", ".join(f"{k} `{v}`" for k, v in args.items())
        answers = "; ".join(f"`{r['caller']}`, {r['step']}: {r['result'].replace('HTTP 200, ', '')}" for r in rows)
        cls = tools.get(name, {}).get("class")
        if cls is None:
            cls = "not exposed" if any("NOT_EXPOSED" in r["result"] for r in rows) else "not recorded"
        out.append(f"| {prompt} | `{name}` | `{cls}` | {answers} |")
    if "DESTRUCTIVE" in recorded_classes:
        out.append("\nA `DESTRUCTIVE` call takes four turns, all visible in section 9: the call is denied with an "
                   "approval request id; a dry run returns a receipt; a different person approves in the admin "
                   "API; the call with the token and receipt runs once, and a replay is refused.")
    elif "MUTATE" in recorded_classes:
        out.append("\nThis profile records `MUTATE` but no `DESTRUCTIVE` tools. Each mutation needs an independent "
                   "per-call approval; no destructive dry-run receipt flow is presented for this profile.")

    out.append("\n## 9. What the run showed\n")
    for label, rows in (("Without Knox", own_rows), ("With Knox", knox_rows)):
        out.append(f"### {label}\n")
        out.append("| # | Step | Caller | Request | Result |")
        out.append("| --- | --- | --- | --- | --- |")
        for r in rows:
            out.append(f"| {r['n']} | {r['step']} | `{r['caller']}` | `{r['request']}` | {r['result']} |")
        out.append("")
    out.append(f"Full requests and responses: [own host](../../examples/adopters/{project}/results/own-host-transcript.log), "
               f"[Knox](../../examples/adopters/{project}/results/knox-transcript.log).")

    out.append("\n## 10. Audit and clean up\n")
    out.append("Every decision, approval and result is in the hash-chained journal. Check a kept one:\n")
    out.append(f"```bash\njava -jar aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar \\\n"
               f"  --verify-audit examples/adopters/{project}/results/own-host-audit.jsonl\n```\n")
    out.append("In your own deployment, add `audit.checkpoint` so the head is exported to storage the journal writer "
               "cannot rewrite. Verify it with `--verify-audit` plus your journal and checkpoint file paths.")
    out.append("\nWhen you ran the full scenario, the test teardown stopped the processes and containers it started. "
               "For a manual run, stop the foreground Aegis, Knox, and engine processes with Ctrl+C in their "
               "terminals, then stop any background process you started from section 4.")
    containers = docker_names(engine)
    if containers:
        out.append("Remove only this tutorial's retained Docker containers after you no longer need their data:\n")
        out.append("```bash\ndocker rm -f " + " ".join(containers) + "\n```")
    out.append("Remove temporary configuration copies, exported certificates, logs, and audit journals only after "
               "retaining any evidence your review or incident process requires.")
    out.append(f"\nLimits of this run are listed in [the example README](../../examples/adopters/{project}/README.md).")
    text = label_test_secrets(unix_classpaths("\n".join(out)))
    return text.rstrip() + "\n", {"topology": topology, "knox_path": knox_path, "server": server,
                                             "test": test, "date": date, "counts": counts}


def ide_readme(project, title, meta):
    """Plain-language guide for the JSON files in this project's ide/ folder."""
    name = f"aegis-{project}"
    own_url = f"http://127.0.0.1:{OWN_HOST_PORT}/mcp"
    knox_url = f"https://localhost:{KNOX_PORT}{meta['knox_path']}"
    walk = f"../../../../docs/tutorials/{project}.md"
    return HEADER_MD + f"""# IDE setup for the {title} tutorial

## What this folder is (new-user version)

Your coding editor's **agent** can call remote tools over MCP. These JSON files tell the editor to call
**Aegis**, not the {title} engine directly. Aegis checks who you are and whether the action is allowed,
then talks to the engine only when the call is allowed.

Think of it as a receptionist: the agent asks Aegis; Aegis decides; the backend never sees a skipped
governance check.

You still need:

1. The real {title} stack running (see the tutorial).
2. Aegis running with that tutorial's configuration on port `{OWN_HOST_PORT}` (own host) or Knox on
   `{KNOX_PORT}` (Knox door).
3. A bearer token (own host) or Knox demo credentials (Knox door) from the tutorial.

Full walkthrough: [`docs/tutorials/{project}.md`]({walk}). Folder overview:
[`adopters/tutorials/README.md`](../../README.md).

## Which file to copy

| Editor | Copy this file to | Door |
| --- | --- | --- |
| VS Code (Copilot agent) | `.vscode/mcp.json` | own host → `{own_url}` |
| VS Code (Knox) | `.vscode/mcp.json` (from `mcp.knox.json`) | Knox → `{knox_url}` |
| Kiro | `.kiro/settings/mcp.json` | own host enabled; Knox entry present but disabled |
| Cursor | `.cursor/mcp.json` | own host → `{own_url}` |

Server name in the files: `{name}` (Knox variant: `{name}-knox`).

## First-time steps

1. Finish the "Run the whole verified scenario" (or Flavor A / Flavor B) section of the tutorial so
   Aegis is listening.
2. Copy the matching JSON above into your project or user MCP settings path.
3. Replace `REPLACE_WITH_YOUR_TOKEN` (Cursor / Kiro) with a token from
   `examples/adopters/{project}/aegis/own-host.yaml`, or answer VS Code's password prompt.
4. Reload MCP / the window, then ask the agent a **read** prompt from the tutorial (for example list
   or status). A write or delete should require approval when the profile marks the tool `MUTATE` or
   `DESTRUCTIVE`.

## What the fields mean

| Field | Meaning |
| --- | --- |
| `url` | MCP endpoint. Must be Aegis (or Knox → Aegis), never the raw engine admin URL. |
| `Authorization: Bearer …` | Own-host identity. The token maps to groups in Aegis config. |
| `Authorization: Basic …` | Knox demo identity. Knox authenticates; Aegis still governs the MCP body. |
| `disabled` (Kiro) | `true` means that server entry is ignored until you enable it. |

## Safety

- Do not commit real tokens or Knox passwords.
- Do not set the IDE to auto-approve destructive tools while learning.
- If the IDE shows no tools, the usual causes are: Aegis not running, wrong port, wrong token, or a
  token whose groups see an empty catalog.
"""


def ide_files(project, meta):
    name = f"aegis-{project}"
    own_url = f"http://127.0.0.1:{OWN_HOST_PORT}/mcp"
    knox_url = f"https://localhost:{KNOX_PORT}{meta['knox_path']}"
    return {
        "vscode/mcp.json": {
            "inputs": [{"type": "promptString", "id": "aegis-token", "password": True,
                        "description": f"Aegis bearer token of the person you act as ({project} tutorial)"}],
            "servers": {name: {"type": "http", "url": own_url,
                               "headers": {"Authorization": "Bearer ${input:aegis-token}"}}}},
        "vscode/mcp.knox.json": {
            "inputs": [{"type": "promptString", "id": "knox-basic", "password": True,
                        "description": "Base64 of a user and password from the local Knox demo LDAP; see conf/users.ldif in that Knox installation"}],
            "servers": {f"{name}-knox": {"type": "http", "url": knox_url,
                                         "headers": {"Authorization": "Basic ${input:knox-basic}"}}}},
        "kiro/mcp.json": {"mcpServers": {
            name: {"url": own_url, "headers": {"Authorization": "Bearer REPLACE_WITH_YOUR_TOKEN"},
                   "disabled": False, "autoApprove": []},
            f"{name}-knox": {"url": knox_url, "headers": {"Authorization": "Basic REPLACE_WITH_BASE64_OF_KNOX_DEMO_CREDENTIALS"},
                             "disabled": True, "autoApprove": []}}},
        "cursor/mcp.json": {"mcpServers": {name: {"url": own_url,
                                                  "headers": {"Authorization": "Bearer REPLACE_WITH_YOUR_TOKEN"}}}},
    }


def index(built, skipped):
    out = [HEADER_MD, "# Project tutorials\n",
           "One tutorial per project that has a retained, clean two-door real run (own host and Knox 3.0.0). "
           "Retained results provide scenario evidence for the tools, outcomes, test counts, and dates. "
           "`tools/build-project-tutorials.py` synthesizes shared commands and tutorial guidance from that "
           "evidence.\n",
           "| Project | Tutorial | Kept run | Tests | IDE files |", "| --- | --- | --- | --- | --- |"]
    for project, meta in sorted(built.items()):
        title = PROJECTS[project][0]
        out.append(f"| {title} | [{project}.md]({project}.md) | `{meta['test']}`, {meta['date']} | "
                   f"{meta['counts'][0]} run, 0 failed, 0 skipped | [`adopters/tutorials/{project}/ide/`](../../adopters/tutorials/{project}/ide/) |")
    out.append("\nApache Spark with Apache Iceberg has its own end-to-end tutorial with seed data, scripts, IDE files "
               "and Knox: [TUTORIAL-SPARK-ICEBERG.md](../TUTORIAL-SPARK-ICEBERG.md).")
    if skipped:
        out.append("\nNot built, and why:\n")
        out += [f"- {s}" for s in skipped]
    out.append("\nProjects with a configuration but no real run on record (Atlas, Cassandra, Hive, Impala, Paimon, "
               "Ranger) have no tutorial here: their profiles pass the contract test only, which proves route "
               "rendering and governance order, not that the product answers. Their status and blockers are in "
               "[examples/adopters/README.md](../../examples/adopters/README.md).")
    return "\n".join(out) + "\n"


def main():
    check = "--check" in sys.argv[1:]
    built, skipped, writes = {}, [], {}
    for project, (title, deep) in PROJECTS.items():
        text, meta = tutorial(project, title, deep)
        if text is None:
            skipped.append(meta)
            continue
        built[project] = meta
        writes[DOCS / f"{project}.md"] = text
    writes[DOCS / "README.md"] = index(built, skipped)
    changed = [p for p, t in writes.items() if not p.is_file() or p.read_text(encoding="utf-8") != t]
    if check:
        for p in changed:
            print("out of date:", p.relative_to(ROOT))
        return 1 if changed else 0
    for p, t in writes.items():
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(t, encoding="utf-8", newline="\n")
    print(f"built {len(built)} tutorials; skipped {len(skipped)}")
    for s in skipped:
        print("  skipped:", s)
    return 0


if __name__ == "__main__":
    sys.exit(main())
