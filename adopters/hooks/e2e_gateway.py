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
"""Live end-to-end run: the gateway jar as a process, a real HTTP backend, the three reference hooks.

Nothing is simulated. The backend is CPython's own http.server serving a directory; the gateway is the
shaded jar started with java -jar; every call is a real MCP 2026-07-28 tools/call over HTTP; the hooks
are the processes in this directory. The CI workflow runs this script after the build.

  python e2e_gateway.py --jar ../../aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar

Exit status 0 when every check passed; each check prints PASS or FAIL with the evidence.
"""

import argparse
import json
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
PROTOCOL = "2026-07-28"


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_http(url, seconds, proc, what):
    deadline = time.time() + seconds
    while time.time() < deadline:
        if proc.poll() is not None:
            raise RuntimeError("%s exited with %s before it was ready" % (what, proc.returncode))
        try:
            with urllib.request.urlopen(url, timeout=2) as r:
                if r.status == 200:
                    return
        except OSError:
            pass
        time.sleep(0.3)
    raise RuntimeError("%s not ready at %s within %ss" % (what, url, seconds))


CONFIG = """
gateway: {{name: aegis-e2e}}
access:
  writeGroups: [writers]
approval:
  keyEnv: AEGIS_SIGNING_KEY
  requiredFor: [DESTRUCTIVE]       # MUTATE runs without an approval here, so the change-window hook decides
policy:
  rules:
    - {{id: e2e, groups: [readers, writers], classes: [READ, MUTATE]}}
egress:
  allowCidrs: [127.0.0.0/8]
  schemes: [http]
audit:
  file: audit.jsonl
hooks:
  - type: remote
    mode: ENFORCE
    settings: {{name: pii-guard, url: "http://127.0.0.1:{pii}/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE, AFTER], includeArguments: true, includeResult: true}}
  - type: remote
    mode: ENFORCE
    settings: {{name: secrets-guard, url: "http://127.0.0.1:{secrets}/", tokenEnv: AEGIS_HOOK_TOKEN,
               phases: [BEFORE], includeArguments: true}}
  - type: remote
    mode: ENFORCE
    settings: {{name: change-window, url: "http://127.0.0.1:{window}/", tokenEnv: AEGIS_HOOK_TOKEN, phases: [BEFORE]}}
sources:
  - id: docs
    type: http
    baseUrl: http://127.0.0.1:{backend}
    tools:
      - name: get_doc
        description: Read one document by name.
        class: READ
        inputSchema:
          type: object
          properties: {{name: {{type: string, maxLength: 128}}}}
          required: [name]
          additionalProperties: false
        request: {{method: GET, path: "/{{name}}"}}
      - name: put_doc
        description: Replace one document.
        class: MUTATE
        inputSchema:
          type: object
          properties: {{name: {{type: string, maxLength: 128}}, text: {{type: string}}}}
          required: [name, text]
          additionalProperties: false
        request: {{method: PUT, path: "/{{name}}", body: {{text: "{{text}}"}}}}
host:
  listen: {{host: 127.0.0.1, port: {gateway}}}
  identity:
    type: static
    static:
      tokens:
        - {{tokenEnv: E2E_CALLER_TOKEN, actor: e2e-agent, groups: [readers, writers]}}
"""


class Run:
    def __init__(self, args):
        self.args = args
        self.work = tempfile.mkdtemp(prefix="aegis-e2e-")
        self.procs = []
        self.failures = 0
        self.ports = {k: free_port() for k in ("backend", "gateway", "pii", "secrets", "window")}
        self.token = secrets.token_hex(24)
        self.env = dict(os.environ, AEGIS_HOOK_TOKEN=secrets.token_hex(24), AEGIS_SIGNING_KEY=secrets.token_hex(32),
                        E2E_CALLER_TOKEN=self.token, PYTHONUNBUFFERED="1")

    def start(self, name, cmd, ready_url, seconds=60):
        log = open(os.path.join(self.work, name + ".log"), "wb")
        p = subprocess.Popen(cmd, env=self.env, cwd=self.work, stdout=log, stderr=subprocess.STDOUT)
        self.procs.append((name, p))
        wait_http(ready_url, seconds, p, name)
        return p

    def check(self, label, ok, evidence):
        print("%s %s: %s" % ("PASS" if ok else "FAIL", label, evidence))
        sys.stdout.flush()
        if not ok:
            self.failures += 1

    def call(self, tool, arguments):
        body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                "params": {"name": tool, "arguments": arguments,
                           "_meta": {"io.modelcontextprotocol/protocolVersion": PROTOCOL,
                                     "io.modelcontextprotocol/clientCapabilities": {}}}}
        req = urllib.request.Request("http://127.0.0.1:%d/mcp" % self.ports["gateway"], data=json.dumps(body).encode(),
                                     headers={"Authorization": "Bearer " + self.token, "Content-Type": "application/json",
                                              "MCP-Protocol-Version": PROTOCOL, "Mcp-Method": "tools/call", "Mcp-Name": tool})
        try:
            with urllib.request.urlopen(req, timeout=20) as r:
                reply = json.loads(r.read())
        except urllib.error.HTTPError as e:
            return {"http": e.code}
        result = reply.get("result") or {}
        decision = (result.get("_meta") or {}).get("io.github.vaquarkhan.aegis/decision") or {}
        text = " ".join(c.get("text", "") for c in result.get("content", []) if isinstance(c, dict))
        return {"outcome": decision.get("outcome"), "code": decision.get("code"),
                "reason": decision.get("reason", ""), "text": text}

    def backend_saw(self, fragment):
        with open(os.path.join(self.work, "backend.log"), "rb") as f:
            return fragment in f.read().decode("utf-8", "replace")

    def main(self):
        docs = os.path.join(self.work, "docs")
        os.makedirs(docs)
        with open(os.path.join(docs, "hello.txt"), "w") as f:
            f.write("hello from the backend\n")
        with open(os.path.join(docs, "customer.txt"), "w") as f:
            f.write("customer card 4111 1111 1111 1111\n")
        with open(os.path.join(self.work, "aegis.yaml"), "w") as f:
            f.write(CONFIG.format(**self.ports))

        py = sys.executable
        p = self.ports
        self.start("backend", [py, "-m", "http.server", str(p["backend"]), "--bind", "127.0.0.1", "--directory", docs],
                   "http://127.0.0.1:%d/hello.txt" % p["backend"])
        self.start("pii", [py, os.path.join(HERE, "pii_guard.py"), "--port", str(p["pii"])], "http://127.0.0.1:%d/healthz" % p["pii"])
        secrets_proc = self.start("secrets", [py, os.path.join(HERE, "secrets_guard.py"), "--port", str(p["secrets"])],
                                  "http://127.0.0.1:%d/healthz" % p["secrets"])
        # An empty --days list means the window is never open: every MUTATE call must be refused.
        self.start("window", [py, os.path.join(HERE, "change_window.py"), "--port", str(p["window"]), "--days", ""],
                   "http://127.0.0.1:%d/healthz" % p["window"])
        self.start("gateway", [self.args.java, "-jar", os.path.abspath(self.args.jar), "--config", "aegis.yaml"],
                   "http://127.0.0.1:%d/readyz" % p["gateway"], seconds=90)

        r = self.call("get_doc", {"name": "hello.txt"})
        self.check("a clean read passes all three hooks and reaches the backend",
                   r.get("outcome") == "ALLOWED" and "hello from the backend" in r.get("text", ""), r)

        r = self.call("get_doc", {"name": "4111111111111111.txt"})
        self.check("pii-guard refuses a card number in an argument, before the backend",
                   r.get("code") == "POLICY_DENIED" and "payment card" in r.get("reason", "")
                   and not self.backend_saw("4111111111111111"), r)

        aws = "AKIA" + "IOSFODNN7EXAMPLE"
        r = self.call("get_doc", {"name": aws + ".txt"})
        self.check("secrets-guard refuses an AWS key id in an argument, before the backend",
                   r.get("code") == "POLICY_DENIED" and "AWS access key" in r.get("reason", "")
                   and not self.backend_saw(aws), r)

        r = self.call("get_doc", {"name": "customer.txt"})
        self.check("pii-guard withholds a result that carries a card number (after phase)",
                   "Output withheld" in r.get("text", "") and "4111" not in json.dumps(r)
                   and self.backend_saw("GET /customer.txt"), r)

        r = self.call("put_doc", {"name": "hello.txt", "text": "changed"})
        self.check("change-window refuses a MUTATE call outside the window, before the backend",
                   r.get("code") == "POLICY_DENIED" and "change window" in r.get("reason", "")
                   and not self.backend_saw("PUT /hello.txt"), r)

        secrets_proc.terminate()
        secrets_proc.wait(10)
        r = self.call("get_doc", {"name": "hello.txt"})
        self.check("with a hook down, the gateway fails closed", r.get("outcome") != "ALLOWED" and r.get("code"), r)

        verify = subprocess.run([self.args.java, "-jar", os.path.abspath(self.args.jar), "--verify-audit",
                                 os.path.join(self.work, "audit.jsonl")], capture_output=True, text=True, env=self.env)
        self.check("the audit journal verifies", verify.returncode == 0 and verify.stdout.startswith("OK"), verify.stdout.strip())
        return self.failures

    def stop(self):
        for name, p in reversed(self.procs):
            if p.poll() is None:
                p.terminate()
                try:
                    p.wait(15)
                except subprocess.TimeoutExpired:
                    p.kill()
        if self.args.keep:
            print("work directory kept: " + self.work)
        else:
            shutil.rmtree(self.work, ignore_errors=True)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--java", default="java")
    ap.add_argument("--keep", action="store_true", help="keep the work directory (logs, audit journal)")
    run = Run(ap.parse_args())
    try:
        failures = run.main()
    except Exception as e:
        print("FAIL setup: %s" % e)
        failures = 1
        run.args.keep = True
    finally:
        run.stop()
    print("%d check(s) failed" % failures if failures else "all checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
