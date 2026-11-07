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
"""A project's own MCP server, standing in for "the MCP server you already have".

Written only with the official MCP Python SDK (pip install mcp==2.2.0), so Aegis is tested against an
MCP implementation it shares no code with. It serves Streamable HTTP; the SDK answers both the
2026-07-28 revision and the 2025 initialize handshake. Its job table is its own in-memory state: the
point of this example is the MCP protocol path and Aegis's governance of it, not a job engine.

Environment:
  ENGINE_MCP_PORT      port on 127.0.0.1
  ENGINE_MCP_TOKEN     the bearer credential this server requires (the gateway's own credential)
  ENGINE_MCP_OBSERVED  file this server appends one JSON line to per HTTP request and per tool call,
                       so the test can see what actually reached it
"""
import json
import os
import threading

import uvicorn
from mcp.server.mcpserver import MCPServer

TOKEN = os.environ["ENGINE_MCP_TOKEN"]
OBSERVED = os.environ["ENGINE_MCP_OBSERVED"]
_lock = threading.Lock()

JOBS = {
    "job-orders-etl": {"jobId": "job-orders-etl", "state": "RUNNING", "owner": "data-eng"},
    "job-scratch-ownhost": {"jobId": "job-scratch-ownhost", "state": "FINISHED", "owner": "data-eng"},
    "job-scratch-knox": {"jobId": "job-scratch-knox", "state": "FINISHED", "owner": "data-eng"},
    "job-scratch-legacy": {"jobId": "job-scratch-legacy", "state": "FINISHED", "owner": "data-eng"},
}


def observe(record: dict) -> None:
    with _lock, open(OBSERVED, "a", encoding="utf-8") as f:
        f.write(json.dumps(record) + "\n")


server = MCPServer(name="engine-mcp", version="1.0.0")


@server.tool(description="List every job with its state.")
def list_jobs() -> dict:
    observe({"tool": "list_jobs"})
    return {"jobs": sorted(JOBS.values(), key=lambda j: j["jobId"])}


@server.tool(description="Get one job.")
def get_job(jobId: str) -> dict:
    observe({"tool": "get_job", "jobId": jobId})
    if jobId not in JOBS:
        raise ValueError(f"no such job: {jobId}")
    return JOBS[jobId]


@server.tool(description="Delete one job and its history.")
def delete_job(jobId: str) -> dict:
    observe({"tool": "delete_job", "jobId": jobId})
    if JOBS.pop(jobId, None) is None:
        raise ValueError(f"no such job: {jobId}")
    return {"deleted": jobId}


@server.tool(description="Restart every job.")
def restart_all_jobs() -> dict:
    # Not listed in existing-mcp-server.yaml: Aegis must treat it as DESTRUCTIVE until classified.
    observe({"tool": "restart_all_jobs"})
    return {"restarted": len(JOBS)}


@server.custom_route("/state", methods=["GET"])
async def state(request):
    """Plain HTTP, outside MCP: the test reads the real job table here, never through Aegis."""
    from starlette.responses import JSONResponse
    return JSONResponse({"jobs": sorted(JOBS)})


mcp_app = server.streamable_http_app(json_response=True)


async def app(scope, receive, send):
    """Requires the gateway's bearer credential and records each request's protocol headers."""
    if scope["type"] == "http":
        headers = {k.decode("latin-1").lower(): v.decode("latin-1") for k, v in scope["headers"]}
        authorized = headers.get("authorization") == "Bearer " + TOKEN
        observe({"http": scope["method"], "authorized": authorized,
                 "protocolVersion": headers.get("mcp-protocol-version"),
                 "session": "mcp-session-id" in headers})
        if not authorized:
            await send({"type": "http.response.start", "status": 401,
                        "headers": [(b"content-type", b"application/json")]})
            await send({"type": "http.response.body", "body": b'{"error":"unauthorized"}'})
            return
    await mcp_app(scope, receive, send)


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=int(os.environ["ENGINE_MCP_PORT"]), log_level="info")
