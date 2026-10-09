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
"""Starts the gateway the way the chart's StatefulSet does, from the chart's rendered output.

Reads `helm template` output, takes aegis.yaml from the ConfigMap and the java arguments from the
StatefulSet, lays out /opt/aegis (gateway jar plus plugins/) in a temporary directory, and runs that
command until /readyz answers 200. Only three things change for a local run: the volume path, the
listen address (127.0.0.1) and the port. Needs PyYAML.

  helm template aegis charts/aegis -f charts/aegis/ci/test-values.yaml > rendered.yaml
  python start_rendered.py rendered.yaml --gateway-jar <...-all.jar> --plugin <aegis-source-a2a.jar>
"""

import argparse
import os
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

import yaml


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("rendered")
    ap.add_argument("--gateway-jar", required=True)
    ap.add_argument("--plugin", action="append", default=[])
    ap.add_argument("--java", default="java")
    a = ap.parse_args()

    with open(a.rendered, encoding="utf-8-sig") as f:
        docs = [d for d in yaml.safe_load_all(f) if d]
    cm = next(d for d in docs if d["kind"] == "ConfigMap")
    sts = next(d for d in docs if d["kind"] == "StatefulSet")
    container = sts["spec"]["template"]["spec"]["containers"][0]
    mount = next(m["mountPath"] for m in container["volumeMounts"] if m["name"] == "data")

    work = tempfile.mkdtemp(prefix="aegis-chart-")
    opt = os.path.join(work, "opt", "aegis")
    os.makedirs(os.path.join(opt, "plugins"))
    shutil.copy(a.gateway_jar, os.path.join(opt, "aegis-gateway.jar"))
    for p in a.plugin:
        shutil.copy(p, os.path.join(opt, "plugins"))
    data = os.path.join(work, "data")
    os.makedirs(data)

    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    port = s.getsockname()[1]
    s.close()
    cfg = yaml.safe_load(cm["data"]["aegis.yaml"])
    cfg["host"]["listen"] = {"host": "127.0.0.1", "port": port}
    cfg["audit"]["file"] = cfg["audit"]["file"].replace(mount, data.replace("\\", "/"))
    cfg_path = os.path.join(work, "aegis.yaml")
    with open(cfg_path, "w") as f:
        yaml.safe_dump(cfg, f)

    args = list(container["args"])
    cp = args.index("-cp") + 1
    args[cp] = os.pathsep.join(os.path.join(opt, x) if x != "plugins/*" else os.path.join(opt, "plugins", "*")
                               for x in (e.replace("/opt/aegis/", "") for e in args[cp].split(":")))
    args[args.index("--config") + 1] = cfg_path
    env = dict(os.environ, AEGIS_SIGNING_KEY=secrets.token_hex(32), CATALOG_TOKEN=secrets.token_hex(16))
    cmd = [a.java if container["command"] == ["java"] else container["command"][0]] + args
    print("command: " + " ".join(cmd))
    log = open(os.path.join(work, "gateway.log"), "wb")
    p = subprocess.Popen(cmd, env=env, stdout=log, stderr=subprocess.STDOUT, cwd=data)
    ok = False
    try:
        for _ in range(200):
            if p.poll() is not None:
                break
            try:
                with urllib.request.urlopen("http://127.0.0.1:%d/readyz" % port, timeout=2) as r:
                    body = r.read().decode()
                    print("readyz %d %s" % (r.status, body))
                    ok = r.status == 200
                    break
            except OSError:
                time.sleep(0.3)
    finally:
        if p.poll() is None:
            p.terminate()
            p.wait(15)
        log.close()
    if not ok:
        with open(os.path.join(work, "gateway.log"), errors="replace") as f:
            print(f.read()[-3000:])
    shutil.rmtree(work, ignore_errors=True)
    print("PASS the rendered release starts and reports ready" if ok else "FAIL the rendered release did not become ready")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
