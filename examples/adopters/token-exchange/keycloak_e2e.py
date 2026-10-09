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
"""RFC 8693 token exchange, end to end, against a real Keycloak (standard token exchange, Keycloak 26.2+).

What it does, with nothing simulated:
  1. Configures a fresh realm through the Keycloak admin REST API: an MCP client whose user tokens are
     issued for the gateway, the gateway's confidential client with standard token exchange enabled,
     a backend audience, and two users in group "analysts".
  2. Starts the Aegis gateway jar with JWT identity from that realm and one http source whose
     credentials are type: token-exchange. The backend is Keycloak's own userinfo endpoint, which
     answers with the user the presented token belongs to.
  3. Calls the tool as alice and as bob over MCP. Each reply names the caller, so the backend saw a
     per-user token, not a shared gateway account and not the inbound token.
  4. Turns token exchange off for the gateway client and shows the call is refused (fail closed).

Python standard library only. Usage:
  python keycloak_e2e.py --keycloak http://127.0.0.1:18180 --admin-password-file pw.txt \
      --jar ../../../aegis-gateway/target/aegis-gateway-2.0.0-SNAPSHOT-all.jar
"""

import argparse
import base64
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
import urllib.parse
import urllib.request

REALM = "aegis-tx-%d" % int(time.time())
PROTOCOL = "2026-07-28"


def http(method, url, body=None, token=None, form=False, expect=(200, 201, 204)):
    headers = {"Accept": "application/json"}
    data = None
    if body is not None:
        if form:
            data = urllib.parse.urlencode(body).encode()
            headers["Content-Type"] = "application/x-www-form-urlencoded"
        else:
            data = json.dumps(body).encode()
            headers["Content-Type"] = "application/json"
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
            status = r.status
    except urllib.error.HTTPError as e:
        raw, status = e.read(), e.code
    if status not in expect:
        raise RuntimeError("%s %s -> HTTP %d %s" % (method, url, status, raw[:300]))
    return json.loads(raw) if raw.strip().startswith(b"{") or raw.strip().startswith(b"[") else None


def claims(jwt):
    part = jwt.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


class Keycloak:
    def __init__(self, base, admin_password):
        self.base = base.rstrip("/")
        self.admin_password = admin_password

    def admin(self):
        t = http("POST", self.base + "/realms/master/protocol/openid-connect/token",
                 {"grant_type": "password", "client_id": "admin-cli", "username": "admin",
                  "password": self.admin_password}, form=True)
        return t["access_token"]

    def setup(self, gateway_secret):
        a = self.admin()
        r = self.base + "/admin/realms"
        http("POST", r, {"realm": REALM, "enabled": True, "accessTokenLifespan": 300}, a)
        rr = r + "/" + REALM

        def audience(name, aud):
            return {"name": name, "protocol": "openid-connect", "protocolMapper": "oidc-audience-mapper",
                    "config": {"included.client.audience": aud, "access.token.claim": "true", "id.token.claim": "false"}}

        groups = {"name": "groups", "protocol": "openid-connect", "protocolMapper": "oidc-group-membership-mapper",
                  "config": {"claim.name": "groups", "full.path": "false", "access.token.claim": "true"}}
        http("POST", rr + "/clients", {"clientId": "orders-api", "publicClient": False, "standardFlowEnabled": False,
                                       "secret": secrets.token_hex(16)}, a)
        http("POST", rr + "/clients", {"clientId": "aegis-gateway", "publicClient": False, "standardFlowEnabled": False,
                                       "directAccessGrantsEnabled": False, "secret": gateway_secret,
                                       "attributes": {"standard.token.exchange.enabled": "true"},
                                       "protocolMappers": [audience("aud-orders-api", "orders-api"), groups]}, a)
        http("POST", rr + "/clients", {"clientId": "mcp-client", "publicClient": True, "standardFlowEnabled": False,
                                       "directAccessGrantsEnabled": True,
                                       "protocolMappers": [audience("aud-aegis-gateway", "aegis-gateway"), groups]}, a)
        http("POST", rr + "/groups", {"name": "analysts"}, a)
        passwords = {}
        for user in ("alice", "bob"):
            pw = secrets.token_hex(12)
            passwords[user] = pw
            http("POST", rr + "/users", {"username": user, "enabled": True, "emailVerified": True,
                                         "email": user + "@example.com", "firstName": user.title(), "lastName": "Tester",
                                         "groups": ["/analysts"],
                                         "credentials": [{"type": "password", "value": pw, "temporary": False}]}, a)
        return passwords

    def set_exchange(self, enabled):
        a = self.admin()
        rr = self.base + "/admin/realms/" + REALM
        cid = http("GET", rr + "/clients?clientId=aegis-gateway", token=a)[0]["id"]
        rep = http("GET", rr + "/clients/" + cid, token=a)
        rep.setdefault("attributes", {})["standard.token.exchange.enabled"] = "true" if enabled else "false"
        http("PUT", rr + "/clients/" + cid, rep, a)

    def user_token(self, user, pw):
        t = http("POST", self.base + "/realms/%s/protocol/openid-connect/token" % REALM,
                 {"grant_type": "password", "client_id": "mcp-client", "username": user, "password": pw,
                  "scope": "openid"}, form=True)
        return t["access_token"]

    def delete_realm(self):
        http("DELETE", self.base + "/admin/realms/" + REALM, token=self.admin(), expect=(204, 404))


CONFIG = """
gateway: {{name: aegis-token-exchange}}
policy:
  rules:
    - {{id: analysts-read, groups: [analysts], classes: [READ]}}
egress:
  allowCidrs: [127.0.0.0/8]
  schemes: [http]
audit:
  file: audit.jsonl
sources:
  - id: orders
    type: http
    baseUrl: {kc}
    credentials:
      type: token-exchange
      tokenEndpoint: {kc}/realms/{realm}/protocol/openid-connect/token
      clientIdEnv: AEGIS_TX_CLIENT_ID
      clientSecretEnv: AEGIS_TX_CLIENT_SECRET
      audience: orders-api
      scope: openid
    tools:
      - name: whoami
        description: The user the backend sees for this call.
        class: READ
        request: {{method: GET, path: /realms/{realm}/protocol/openid-connect/userinfo}}
host:
  listen: {{host: 127.0.0.1, port: {port}}}
  identity:
    type: jwt
    jwt:
      issuer: {kc}/realms/{realm}
      audience: aegis-gateway
      jwksUri: {kc}/realms/{realm}/protocol/openid-connect/certs
      algorithms: [RS256]
      groupsClaim: groups
"""


def mcp(port, token, tool):
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": {"name": tool, "arguments": {},
                       "_meta": {"io.modelcontextprotocol/protocolVersion": PROTOCOL,
                                 "io.modelcontextprotocol/clientCapabilities": {}}}}
    req = urllib.request.Request("http://127.0.0.1:%d/mcp" % port, data=json.dumps(body).encode(),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/json",
                                          "MCP-Protocol-Version": PROTOCOL, "Mcp-Method": "tools/call", "Mcp-Name": tool})
    with urllib.request.urlopen(req, timeout=30) as r:
        result = json.loads(r.read()).get("result") or {}
    d = (result.get("_meta") or {}).get("io.github.vaquarkhan.aegis/decision") or {}
    text = " ".join(c.get("text", "") for c in result.get("content", []) if isinstance(c, dict))
    return d.get("outcome"), d.get("code"), text


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--keycloak", required=True)
    ap.add_argument("--admin-password-file", required=True)
    ap.add_argument("--jar", required=True)
    ap.add_argument("--java", default="java")
    args = ap.parse_args()
    with open(args.admin_password_file) as f:
        kc = Keycloak(args.keycloak, f.read().strip())
    failures = 0

    def check(label, ok, evidence):
        nonlocal failures
        print("%s %s: %s" % ("PASS" if ok else "FAIL", label, evidence))
        sys.stdout.flush()
        failures += 0 if ok else 1

    gateway_secret = secrets.token_hex(24)
    work = tempfile.mkdtemp(prefix="aegis-tx-")
    gw = None
    try:
        passwords = kc.setup(gateway_secret)
        alice = kc.user_token("alice", passwords["alice"])
        c = claims(alice)
        check("the user token is issued for the gateway, not the backend",
              "aegis-gateway" in (c["aud"] if isinstance(c["aud"], list) else [c["aud"]]) and "orders-api" not in str(c["aud"]),
              {"aud": c["aud"], "azp": c.get("azp"), "groups": c.get("groups")})

        port = free_port()
        with open(os.path.join(work, "aegis.yaml"), "w") as f:
            f.write(CONFIG.format(kc=kc.base, realm=REALM, port=port))
        env = dict(os.environ, AEGIS_TX_CLIENT_ID="aegis-gateway", AEGIS_TX_CLIENT_SECRET=gateway_secret)
        log = open(os.path.join(work, "gateway.log"), "wb")
        gw = subprocess.Popen([args.java, "-jar", os.path.abspath(args.jar), "--config", "aegis.yaml"], cwd=work, env=env,
                              stdout=log, stderr=subprocess.STDOUT)
        for _ in range(300):
            try:
                if urllib.request.urlopen("http://127.0.0.1:%d/readyz" % port, timeout=2).status == 200:
                    break
            except OSError:
                if gw.poll() is not None:
                    raise RuntimeError("gateway exited; see " + os.path.join(work, "gateway.log"))
                time.sleep(0.3)

        outcome, code, text = mcp(port, alice, "whoami")
        check("alice's call reaches the backend with a token for alice", outcome == "ALLOWED" and '"preferred_username":"alice"' in text,
              (outcome, code, text[:160]))
        bob = kc.user_token("bob", passwords["bob"])
        outcome, code, text = mcp(port, bob, "whoami")
        check("bob's call reaches the backend with a token for bob", outcome == "ALLOWED" and '"preferred_username":"bob"' in text,
              (outcome, code, text[:160]))

        with open(os.path.join(work, "audit.jsonl")) as f:
            journal = f.read()
        check("no token appears in the audit journal", alice not in journal and bob not in journal and "eyJ" not in journal,
              "%d records" % journal.count("\n"))

        kc.set_exchange(False)
        fresh = kc.user_token("alice", passwords["alice"])  # a new subject token, so the cache cannot answer
        outcome, code, text = mcp(port, fresh, "whoami")
        check("with exchange disabled at Keycloak, the call is refused, with no fallback credential",
              outcome != "ALLOWED" and code == "BACKEND_ERROR", (outcome, code, text[:160]))
    except Exception as e:
        check("run", False, repr(e))
    finally:
        if gw is not None:
            gw.terminate()
            try:
                gw.wait(15)
            except subprocess.TimeoutExpired:
                gw.kill()
        try:
            kc.delete_realm()
        except Exception as e:  # cleanup only
            print("note: realm cleanup failed: %r" % e)
        if failures:
            print("work directory kept: " + work)
        else:
            shutil.rmtree(work, ignore_errors=True)
    print("%d check(s) failed" % failures if failures else "all checks passed")
    sys.exit(1 if failures else 0)


if __name__ == "__main__":
    main()
