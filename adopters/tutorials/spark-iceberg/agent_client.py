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
"""Connect to Aegis with the official MCP Python SDK, the same way an IDE or agent framework does.

    pip install "mcp==2.2.0"
    python agent_client.py --token "$READER_TOKEN" list
    python agent_client.py --token "$READER_TOKEN" call spark_preview_table '{"table": "lake.sales.orders"}'
    # Knox flavor (HTTP Basic against Knox's demo LDAP; trust Knox's exported certificate):
    python agent_client.py --url https://localhost:8443/gateway/aegis-spark-iceberg/aegis-mcp/v1/mcp \
        --basic "$KNOX_USER:$KNOX_PASSWORD" --cafile knox-identity.pem list

A governance decision comes back inside the tool result: `isError` plus the reason text, and the Aegis
decision under `_meta` (`io.github.vaquarkhan.aegis/decision`), for example an approval request id.
"""
import argparse
import asyncio
import base64
import json

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def main() -> None:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--url", default="http://127.0.0.1:8080/mcp")
    p.add_argument("--token", help="own host: bearer token")
    p.add_argument("--basic", help="Knox: user:password")
    p.add_argument("--cafile", help="PEM certificate to trust (Knox's self-signed localhost certificate)")
    p.add_argument("command", choices=["list", "call"])
    p.add_argument("tool", nargs="?")
    p.add_argument("arguments", nargs="?", default="{}")
    p.add_argument("meta", nargs="?", default="{}", help="Aegis controls, e.g. {\"io.github.vaquarkhan.aegis/dryRun\": true}")
    a = p.parse_args()
    if a.token:
        auth = "Bearer " + a.token
    elif a.basic:
        auth = "Basic " + base64.b64encode(a.basic.encode()).decode()
    else:
        p.error("pass --token (own host) or --basic (Knox)")

    http = httpx.AsyncClient(headers={"Authorization": auth}, verify=a.cafile or True, timeout=60)
    async with streamable_http_client(a.url, http_client=http) as (read, write):
        async with ClientSession(read, write) as session:
            info = await session.initialize()
            print(f"connected to {info.server_info.name} {info.server_info.version}, protocol {info.protocol_version}")
            if a.command == "list":
                for tool in (await session.list_tools()).tools:
                    print(f"  {tool.name}: {tool.description}")
                return
            await session.list_tools()
            result = await session.call_tool(a.tool, json.loads(a.arguments), meta=json.loads(a.meta))
            print(json.dumps(result.model_dump(mode="json", by_alias=True, exclude_none=True), indent=2))


if __name__ == "__main__":
    asyncio.run(main())
