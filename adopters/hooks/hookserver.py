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
"""The shared part of the reference hook services: an HTTP listener for the Aegis remote-hook contract.

Python standard library only. Contract (see RemoteHook.java):
  POST any path, JSON body {"aegis":"2.0","phase":"before|afterTool|afterContent", ...}
  reply 200 {"allow": true} or 200 {"allow": false, "code": "...", "reason": "..."}
Anything else (a 4xx, a 5xx, a body that is not that shape) makes Aegis deny in ENFORCE mode, so a
service that cannot decide answers 400 or 500 and the gateway fails closed.

A reason never contains the matched value, only where it was found, because the reason is written to
the audit journal and returned to the caller.
"""

import argparse
import hmac
import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

MAX_BODY = 1 << 20
ALLOWED_CODES = {"POLICY_DENIED", "INVALID_INPUT", "PROMPT_INJECTION"}


def allow():
    return {"allow": True}


def deny(reason, code="POLICY_DENIED"):
    if code not in ALLOWED_CODES:
        code = "POLICY_DENIED"
    return {"allow": False, "code": code, "reason": reason}


def strings(value, path=""):
    """Yields (json_pointer, text) for every string inside a JSON value, keys included."""
    stack = [(path, value, 0)]
    while stack:
        p, v, depth = stack.pop()
        if depth > 64:
            raise ValueError("value nested deeper than 64 levels")
        if isinstance(v, str):
            yield (p or "/", v)
        elif isinstance(v, dict):
            for k, x in v.items():
                child = p + "/" + str(k).replace("~", "~0").replace("/", "~1")
                # A key is reported by its parent: the key itself may be the sensitive value.
                yield ((p or "") + "/ (a key)", str(k))
                stack.append((child, x, depth + 1))
        elif isinstance(v, list):
            for i, x in enumerate(v):
                stack.append((p + "/" + str(i), x, depth + 1))


def parser(description):
    """The listener options every reference hook shares; scripts add their own."""
    ap = argparse.ArgumentParser(description=description)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--token-env", default="AEGIS_HOOK_TOKEN",
                    help="environment variable holding the bearer token Aegis sends (settings.tokenEnv); "
                         "when it is set, requests without it are refused")
    return ap


def serve(name, decide, args):
    """Runs the listener. decide(request_dict) returns allow() or deny(...), or raises to fail closed."""
    token = os.environ.get(args.token_env)

    class Handler(BaseHTTPRequestHandler):
        server_version = name

        def log_message(self, fmt, *a):
            sys.stderr.write("%s %s\n" % (name, fmt % a))

        def reply(self, status, obj):
            data = json.dumps(obj).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self):
            if self.path == "/healthz":
                self.reply(200, {"status": "ok"})
            else:
                self.reply(404, {"error": "not found"})

        def do_POST(self):
            if token is not None:
                sent = self.headers.get("Authorization", "")
                if not hmac.compare_digest(sent.encode("utf-8"), ("Bearer " + token).encode("utf-8")):
                    self.reply(401, {"error": "unauthorized"})
                    return
            try:
                length = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                length = -1
            if length < 0 or length > MAX_BODY:
                self.reply(413, {"error": "body too large"})
                return
            try:
                req = json.loads(self.rfile.read(length).decode("utf-8"))
                if not isinstance(req, dict) or req.get("aegis") != "2.0":
                    raise ValueError("not an Aegis 2.0 hook request")
                answer = decide(req)
            except Exception as e:  # fail closed: Aegis denies on any non-200
                self.reply(400, {"error": type(e).__name__})
                return
            self.reply(200, answer)

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    sys.stderr.write("%s listening on http://%s:%d/\n" % (name, args.host, args.port))
    sys.stderr.flush()
    try:
        httpd.serve_forever()
    finally:
        httpd.server_close()
