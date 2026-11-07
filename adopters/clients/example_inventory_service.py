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
"""A small functional inventory service used by the client-language demonstration.

It owns mutable inventory state and records requests, which lets the demo prove that a denied call
never reaches a backend. It is an example service, not evidence for compatibility with another product.

    GET    /things        -> current inventory
    DELETE /things/<id>   -> 204 and removes the item; 404 when absent
    GET    /_received     -> requests received by the service, as JSON
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, HTTPServer

RECEIVED = []
THINGS = [{"id": "t-1", "name": "orders"}, {"id": "t-2", "name": "payments"}]


class Handler(BaseHTTPRequestHandler):
    def _send(self, status, payload=None):
        body = b"" if payload is None else json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        if self.path == "/_received":
            self._send(200, RECEIVED)
            return
        RECEIVED.append("GET " + self.path)
        if self.path == "/things":
            self._send(200, {"things": THINGS})
        else:
            self._send(404, {"error": "no such path"})

    def do_DELETE(self):
        RECEIVED.append("DELETE " + self.path)
        prefix = "/things/"
        if not self.path.startswith(prefix) or len(self.path) == len(prefix):
            self._send(404, {"error": "no such path"})
            return
        item_id = self.path[len(prefix):]
        for index, item in enumerate(THINGS):
            if item["id"] == item_id:
                del THINGS[index]
                self._send(204)
                return
        self._send(404, {"error": "no such thing", "id": item_id})

    def log_message(self, fmt, *args):
        pass  # keep the demo transcript readable


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9109
    print("example inventory service listening on http://127.0.0.1:%d" % port, flush=True)
    HTTPServer(("127.0.0.1", port), Handler).serve_forever()
