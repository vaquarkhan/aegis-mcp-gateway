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
"""A small A2A 1.0 agent served by the official a2a-sdk (pip install "a2a-sdk[http-server]==1.2.1" uvicorn).

It keeps an in-memory stock table and understands two kinds of message:
  "stock SKU-1"            -> replies with the units on hand (a Message)
  "reserve 3 SKU-1"        -> reserves units and replies with a completed Task and an artifact
Anything else gets a Task in TASK_STATE_INPUT_REQUIRED, so a client can see that state too.

It also writes every message it receives to the file named by --journal, one JSON line each, so a test
can prove which calls reached the agent and which were stopped before it.

Usage: python inventory_agent.py --port 9999 [--journal received.jsonl] [--require-token TOKEN]
"""

import argparse
import json
import re
import uuid

import uvicorn
from starlette.applications import Starlette
from starlette.middleware.base import BaseHTTPMiddleware
from starlette.responses import JSONResponse

from a2a.helpers.proto_helpers import new_text_artifact, new_text_message
from a2a.server.agent_execution import AgentExecutor, RequestContext
from a2a.server.events import EventQueue
from a2a.server.request_handlers import DefaultRequestHandlerV2
from a2a.server.routes import create_agent_card_routes, create_jsonrpc_routes
from a2a.server.tasks import InMemoryTaskStore
from a2a.types.a2a_pb2 import (
    AgentCapabilities,
    AgentCard,
    AgentInterface,
    AgentSkill,
    Task,
    TaskState,
    TaskStatus,
)

STOCK = {"SKU-1": 12, "SKU-2": 0}


class InventoryExecutor(AgentExecutor):
    def __init__(self, journal):
        self.journal = journal

    async def execute(self, context: RequestContext, event_queue: EventQueue) -> None:
        text = context.get_user_input().strip()
        if self.journal:
            with open(self.journal, "a", encoding="utf-8") as f:
                f.write(json.dumps({"text": text}) + "\n")
        stock = re.fullmatch(r"stock (SKU-\d+)", text)
        reserve = re.fullmatch(r"reserve (\d+) (SKU-\d+)", text)
        if stock:
            sku = stock.group(1)
            await event_queue.enqueue_event(new_text_message(
                f"{STOCK.get(sku, 0)} units of {sku} on hand", context_id=context.context_id))
            return
        task_id = context.task_id or str(uuid.uuid4())
        context_id = context.context_id or str(uuid.uuid4())
        if reserve:
            units, sku = int(reserve.group(1)), reserve.group(2)
            if STOCK.get(sku, 0) < units:
                state, artifacts = TaskState.TASK_STATE_FAILED, []
                note = f"only {STOCK.get(sku, 0)} units of {sku} on hand"
            else:
                STOCK[sku] -= units
                state = TaskState.TASK_STATE_COMPLETED
                artifacts = [new_text_artifact(name="reservation", text=f"reserved {units} units of {sku}")]
                note = f"{STOCK[sku]} units of {sku} left"
        else:
            state, artifacts, note = TaskState.TASK_STATE_INPUT_REQUIRED, [], "say 'stock SKU-n' or 'reserve N SKU-n'"
        await event_queue.enqueue_event(Task(
            id=task_id, context_id=context_id, artifacts=artifacts,
            status=TaskStatus(state=state, message=new_text_message(note, context_id=context_id, task_id=task_id))))

    async def cancel(self, context: RequestContext, event_queue: EventQueue) -> None:
        raise NotImplementedError("cancel is not supported")


class RequireToken(BaseHTTPMiddleware):
    """Optional bearer check on the JSON-RPC route; the agent card stays public, as A2A expects."""

    def __init__(self, app, token):
        super().__init__(app)
        self.token = token

    async def dispatch(self, request, call_next):
        if self.token and request.url.path == "/rpc" and request.headers.get("authorization") != "Bearer " + self.token:
            return JSONResponse({"error": "unauthorized"}, status_code=401)
        return await call_next(request)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, required=True)
    p.add_argument("--journal")
    p.add_argument("--require-token")
    a = p.parse_args()
    base = f"http://127.0.0.1:{a.port}"
    card = AgentCard(
        name="Inventory Agent",
        description="Answers stock questions and reserves stock for orders.",
        version="1.0.0",
        supported_interfaces=[AgentInterface(url=base + "/rpc", protocol_binding="JSONRPC", protocol_version="1.0")],
        capabilities=AgentCapabilities(streaming=False),
        default_input_modes=["text/plain"],
        default_output_modes=["text/plain"],
        skills=[
            AgentSkill(id="stock", name="Stock lookup", description="Units on hand for a SKU.", tags=["inventory"]),
            AgentSkill(id="reserve", name="Reserve stock", description="Holds units of a SKU for an order.", tags=["inventory"]),
        ],
    )
    handler = DefaultRequestHandlerV2(agent_executor=InventoryExecutor(a.journal), task_store=InMemoryTaskStore(), agent_card=card)
    app = Starlette(routes=create_agent_card_routes(card) + create_jsonrpc_routes(handler, "/rpc"))
    if a.require_token:
        app.add_middleware(RequireToken, token=a.require_token)
    uvicorn.run(app, host="127.0.0.1", port=a.port, log_level="warning")


if __name__ == "__main__":
    main()
