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
# Apache Burr example: with Knox and without Knox

This example runs the shipped profile
[`adopters/configurations/adapters/burr/burr.yaml`](../../../adopters/configurations/adapters/burr/burr.yaml)
against a real Apache Burr (incubating) 0.42.0 tracking server, through Aegis's own server and inside a stock Apache
Knox 3.0.0 gateway. Only the server address and the egress network are substituted.

Status: **retained historical run**, both front doors. The kept logs are in [results/](results/).

## Files in this folder

| File | What it is |
| --- | --- |
| [`HOW-TO-RUN.md`](HOW-TO-RUN.md) | written by the test harness during the kept run: the exact command line each engine was started with, how each front door was started with the files below, a sample `curl` request, and every request the test sent with its result. Start here to repeat the run by hand |
| [`aegis/own-host.yaml`](aegis/own-host.yaml) | the Aegis configuration the own-host door loaded: the shipped profile with the engine address substituted, plus a `host:` section |
| [`aegis/knox-aegis.yaml`](aegis/knox-aegis.yaml) | the Aegis configuration loaded inside Knox (no `host:` section) |
| [`aegis/knox-topology.xml`](aegis/knox-topology.xml) | the Knox topology: demo LDAP authentication, group mapping, `AclsAuthz`, and the `AEGIS_MCP` service |
| [`setup.ps1`](setup.ps1) | installs the pinned Python or Node servers this example needs, outside the repository |
| [`setup.sh`](setup.sh) | the same, for Linux and macOS |
| [`record_runs.py`](record_runs.py) | records the Burr application runs the tracking server serves |
| [`results/`](results/) | the kept transcripts, Aegis audit journals and Surefire report of that run |
| [`BurrFrontDoorsTest.java`](../../aegis-validation/src/test/java/io/github/vaquarkhan/aegis/validation/BurrFrontDoorsTest.java) | the test itself: how the engine is started and seeded, and every assertion |

## How the run works

1. `mvn -B -Preal-engines -DskipTests install` resolves the engine (the `examples/aegis-validation-*-server` module for this adopter, or a distribution named in this README) and builds the Aegis jars, including the Knox bundle.
2. `BurrFrontDoorsTest` starts the real engine in its own process, as section 1 of `HOW-TO-RUN.md` shows, and creates the data the scenario works on through the engine's own API, outside Aegis.
3. Without Knox: the test builds Aegis from `aegis/own-host.yaml`, starts Aegis's own server on loopback, and sends MCP `tools/call` requests over HTTP as each person, with that person's bearer token.
4. With Knox: the test copies the Aegis bundle into a stock Knox 3.0.0 `ext/`, writes `aegis/knox-topology.xml` to `conf/topologies/`, starts Knox's demo LDAP and the gateway, and sends the same requests with HTTP Basic as the same people. Knox authenticates and checks the route ACL; Aegis governs the MCP call.
5. After every governed write, the test reads the engine directly to check what really changed. Each request and response goes to the transcript, and the Aegis audit journal is copied next to it.

## What runs

| Step | What | How |
| --- | --- | --- |
| 1 | two application runs, `run-ownhost` and `run-knox`, in project `support-agent` | [`record_runs.py`](record_runs.py): a two-action Burr application tracked by Burr's own `LocalTrackingClient`. No model is called; the state it records (a customer question, a retrieved policy, an answer) is the kind a real agent trace carries |
| 2 | the tracking server | `python -m uvicorn burr.tracking.server.run:app` (what the `burr` command runs) with `BURR_PATH` on that store and the UI static files off |

## What the test proves

This profile governs an observability store for agents, so its interesting control is on a READ.

| Step | Caller | Call | Own host | Knox |
| --- | --- | --- | --- | --- |
| 1 | `guest` (reader) | `burr_get_version`, `burr_list_projects`, `burr_list_applications` | allowed; `0.42.0`, the real project and runs | allowed |
| 2 | `admin` (outsider) | the same reads | Aegis denies: `POLICY_DENIED` | Knox `AclsAuthz`: HTTP 403 |
| 3 | `guest` | `burr_get_application_trace` | `NOT_EXPOSED`: the trace is listed only to `burr-operators` (`allowedGroups`) | same |
| 4 | `sam` (operator) | the same trace | allowed; every step with its recorded state | same |
| 5 | `guest` | `burr_annotate_step` (`MUTATE`) | `READONLY_CALLER` | `READONLY_CALLER` |
| 6 | `sam` | the same call | `APPROVAL_REQUIRED`; no annotation on the run | same |
| 7 | `sam` | dry run | an approval request, no receipt (receipts are for `DESTRUCTIVE`); still no annotation | same |
| 8 | `sam` | approves own request | HTTP 403 | same |
| 9 | `tom` (approver) | approves | HTTP 200 | same |
| 10 | `sam` | annotate with the token | allowed; the tracking server stores exactly one annotation on the run | same |
| 11 | `sam` | same token again | denied: single use | same |

Annotations are read from `GET /api/v0/support-agent/annotations` directly, outside Aegis.

| Person | Groups in this profile |
| --- | --- |
| `guest` | `burr-users` |
| `sam` | `burr-operators` |
| `tom` | `burr-approvers` |
| `admin` | none, and outside the Knox ACL |

## Run it

```powershell
./setup.ps1 -Destination C:\aegis-engines     # burr[tracking]==0.42.0, setuptools==80.9.0
$env:AEGIS_BURR_PYTHON = 'C:\aegis-engines\py-burr\Scripts\python.exe'
$env:AEGIS_KNOX_HOME = 'C:\knox\knox-3.0.0'
../run-example.ps1 burr -Keep
```

```bash
./setup.sh /opt/aegis-engines
export AEGIS_BURR_PYTHON=/opt/aegis-engines/py-burr/bin/python
export AEGIS_KNOX_HOME=/opt/knox/knox-3.0.0
../run-example.sh burr --keep
```

Without `AEGIS_BURR_PYTHON` the test is skipped, never passed.

## Findings from the real server

- `burr_annotate_step` and `burr_update_annotation` now require `tags` and `thumbsUp`. Burr 0.42.0 answers
  422 when `tags` or an observation's `thumbs_up_thumbs_down` is missing, and Aegis drops an absent
  optional argument from the body.
- `/api/v0/version` imports `pkg_resources`, which a fresh Python 3.13 venv does not have; without
  setuptools it answers HTTP 500. `setup.ps1` pins setuptools for that reason.
- `burr_list_indexing_jobs` answers 404 ("This backend does not support indexing jobs") on the local
  backend. It is meant for an indexing backend such as S3, and is not called here.

## Results

| File | What it is |
| --- | --- |
| `results/own-host-transcript.log`, `results/knox-transcript.log` | every request and response, per front door |
| `results/own-host-audit.jsonl`, `results/knox-audit.jsonl` | the Aegis journals; verify with `--verify-audit` |
| `results/test-report.txt` | the Surefire summary |

## Limits

- The tracking server has no authentication of its own; keep it on a network only the gateway reaches.
- `burr_update_annotation` follows the same approval path and is covered by `AdapterConfigsTest`, not run here.
