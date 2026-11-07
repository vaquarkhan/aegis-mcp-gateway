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
"""Drive a governed Aegis gateway from Python, using only the standard library.

No MCP SDK, no HTTP library, no Aegis client library. In MCP 2026-07-28 there is no handshake and no
session: every request is a JSON-RPC POST that carries its own protocol version, so anything that can
POST JSON is an MCP client. Governance is decided entirely by the gateway.

Run:  python aegis_client.py http://127.0.0.1:8099/mcp
Environment: ANALYST_TOKEN, OPERATOR_TOKEN
"""
import json
import os
import sys
import urllib.error
import urllib.request

PROTOCOL = "2026-07-28"
META_VERSION = "io.modelcontextprotocol/protocolVersion"
AEGIS_DRY_RUN = "io.github.vaquarkhan.aegis/dryRun"


def rpc(url, token, method, params=None, tool=None, request_id=1):
    """One JSON-RPC call. The version travels in both the header and params._meta, and must agree."""
    body = {"jsonrpc": "2.0", "id": request_id, "method": method, "params": dict(params or {})}
    body["params"].setdefault("_meta", {})[META_VERSION] = PROTOCOL
    headers = {
        "Content-Type": "application/json",
        "Accept": "application/json",
        "Authorization": "Bearer " + token,
        "MCP-Protocol-Version": PROTOCOL,
        "Mcp-Method": method,
    }
    if tool:
        headers["Mcp-Name"] = tool
    req = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=15) as r:
            return json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode() or "{}")


def call_tool(url, token, name, arguments, request_id, meta=None):
    params = {"name": name, "arguments": arguments}
    if meta:
        params["_meta"] = dict(meta)
    return rpc(url, token, "tools/call", params, tool=name, request_id=request_id)


def tool_text(response):
    for part in response.get("result", {}).get("content", []):
        if part.get("type") == "text":
            return part.get("text", "")
    return ""


def decision(response):
    """Aegis puts the governance decision in result._meta, so a client can show why it was refused."""
    meta = response.get("result", {}).get("_meta", {})
    for key, value in meta.items():
        if key.endswith("/decision"):
            return value
    return {}


def main(url):
    analyst = os.environ["ANALYST_TOKEN"]
    operator = os.environ["OPERATOR_TOKEN"]

    print("== python %s, standard library only, MCP %s, no handshake and no session =="
          % (sys.version.split()[0], PROTOCOL), flush=True)

    listed = rpc(url, analyst, "tools/list", {}, request_id=1)
    names = [t["name"] for t in listed.get("result", {}).get("tools", [])]
    print("tools/list as analyst   -> %s" % names, flush=True)
    print("                           the destructive tool is not even advertised to a reader", flush=True)

    read = call_tool(url, analyst, "demo_list_things", {}, 2)
    print("READ as analyst         -> %s" % tool_text(read)[:80], flush=True)

    denied = call_tool(url, analyst, "demo_delete_thing", {"id": "t-1"}, 3)
    d = decision(denied)
    print("DESTRUCTIVE as analyst  -> refused %s at step %s" % (d.get("code"), d.get("step")), flush=True)

    needs = call_tool(url, operator, "demo_delete_thing", {"id": "t-1"}, 4)
    nd = decision(needs)
    print("DESTRUCTIVE as operator -> refused %s at step %s" % (nd.get("code"), nd.get("step")), flush=True)

    dry = call_tool(url, operator, "demo_delete_thing", {"id": "t-1"}, 5, {AEGIS_DRY_RUN: True})
    dd = decision(dry)
    print("dry run as operator     -> allowed, receipt=%s approvalRequest=%s"
          % (bool(dd.get("dryRunReceipt")), bool(dd.get("approvalRequestId"))), flush=True)

    bad = call_tool(url, analyst, "demo_list_things", {"unexpected": 1}, 6)
    bd = decision(bad)
    print("undeclared argument     -> refused %s at step %s" % (bd.get("code"), bd.get("step")), flush=True)
    print("python client done", flush=True)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8099/mcp")
