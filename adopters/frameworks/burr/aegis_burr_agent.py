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
"""An Apache Burr (incubating) agent whose only route to a data system is a governed Aegis gateway.

WHAT THIS DEMONSTRATES

Burr expresses an agent as a state machine of actions over a graph. That is the right shape for an
operator assistant: investigate, decide, act, and record why. What Burr deliberately does not do is
decide whether an action is *permitted*. It has no notion of a caller, a group, an approval, or a
governed object, and it should not: it is an in-process Python framework, and a framework that granted
permissions would be granting them to itself.

So the interesting boundary is between the two. This file puts the whole decision graph in Burr and the
whole authority question in Aegis:

    Burr decides WHAT to try, in WHICH ORDER, and WHEN TO STOP.
    Aegis decides WHETHER this caller may do it, on THIS object, RIGHT NOW, and records the answer.

The agent below runs a realistic incident flow against the Flink tutorial gateway:

    triage  -> read the cluster and the failing job            (READ, allowed)
    decide  -> choose an action from what it actually observed
    attempt -> try to stop the job without an approval          (refused, APPROVAL_REQUIRED)
    request -> take the dry run, which opens an approval request and returns a receipt
    wait    -> a human approves out of band; the agent cannot approve its own request
    promote -> retry with the approval token and the receipt    (allowed)
    report  -> summarise, including every refusal and why

The refusals are the point. An agent that is never refused has not been governed, it has been trusted.
Each denial comes back with a stable code and the step it failed at, which is enough for the state
machine to branch on rather than guess.

RUN IT

    # 1. bring up the Flink tutorial gateway (docs/TUTORIAL-FLINK.md parts 4.2 to 4.5)
    # 2. pip install burr
    # 3. export the same tokens the gateway was started with
    export OPERATOR_TOKEN='operator-token-0123456789'
    export APPROVER_TOKEN='approver-token-0123456789'
    python aegis_burr_agent.py

Burr is optional. With `burr` installed the graph runs as a real Burr application and each step is
tracked; without it the same actions run in sequence so the governance flow can be seen with nothing
but the standard library. Both paths call the identical functions; the standard-library path is an untracked execution mode, not a simulation.
"""
import json
import os
import sys
import time
import urllib.error
import urllib.request

GATEWAY = os.environ.get("AEGIS_URL", "http://127.0.0.1:8080/mcp")
ADMIN = os.environ.get("AEGIS_ADMIN_URL", "http://127.0.0.1:8080/aegis/v1")
PROTOCOL = "2026-07-28"
META_VERSION = "io.modelcontextprotocol/protocolVersion"
DRY_RUN = "io.github.vaquarkhan.aegis/dryRun"
APPROVAL = "io.github.vaquarkhan.aegis/approvalToken"
RECEIPT = "io.github.vaquarkhan.aegis/dryRunReceipt"
# .strip() is not decoration. On Windows, `set VAR=value && python ...` puts the space before the &&
# into the value, Flink then tries to create a directory whose name ends in a space, and the savepoint
# fails with an IOException that looks like a permissions problem. Half an hour of this run was spent
# finding that out.
SAVEPOINT_DIR = os.environ.get("AEGIS_SAVEPOINT_DIR", "file:///tmp/aegis-savepoints").strip()

# ---------------------------------------------------------------------------- the MCP client
# Twenty lines, standard library only. MCP 2026-07-28 removed the handshake and the session, so a
# client is a thing that can POST JSON. There is no Aegis SDK to depend on, by design.


def call(token, tool, arguments=None, meta=None, method="tools/call"):
    params = {"name": tool, "arguments": dict(arguments or {})} if method == "tools/call" else {}
    params["_meta"] = dict(meta or {})
    params["_meta"][META_VERSION] = PROTOCOL
    body = {"jsonrpc": "2.0", "id": 1, "method": method, "params": params}
    headers = {"Content-Type": "application/json", "Accept": "application/json",
               "Authorization": "Bearer " + token, "MCP-Protocol-Version": PROTOCOL,
               "Mcp-Method": method}
    if method == "tools/call":
        headers["Mcp-Name"] = tool
    req = urllib.request.Request(GATEWAY, data=json.dumps(body).encode(), method="POST", headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode() or "{}")
        except ValueError:
            return {"error": {"message": "HTTP %s" % e.code}}


def decision(response):
    """The governance decision Aegis attaches to every answer, allowed or refused."""
    for key, value in (response.get("result", {}).get("_meta", {}) or {}).items():
        if key.endswith("/decision"):
            return value
    data = response.get("error", {}).get("data", {})
    return data if isinstance(data, dict) else {}


def text(response):
    for part in response.get("result", {}).get("content", []):
        if part.get("type") == "text":
            return part.get("text", "")
    return ""


def admin(token, path, method="GET"):
    req = urllib.request.Request(ADMIN + path, method=method,
                                 headers={"Authorization": "Bearer " + token})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, json.loads(r.read().decode() or "{}")
    except urllib.error.HTTPError as e:
        return e.code, {}


# ---------------------------------------------------------------------------- the agent's steps
# Each function is one Burr action: it takes state, does one governed thing, and returns what it
# learned plus what governance said. None of them decide whether they are allowed.


def stop_args(state):
    return {"jobId": state["target"], "targetDirectory": SAVEPOINT_DIR, "drain": False}


def triage(state):
    """READ the cluster and the running jobs. An analyst could do this; nothing is risked."""
    token = os.environ["OPERATOR_TOKEN"]
    cluster = call(token, "flink_get_cluster_info")
    jobs = call(token, "flink_list_jobs")
    running = []
    try:
        running = [j for j in json.loads(text(jobs) or "[]") if j.get("state") == "RUNNING"]
    except ValueError:
        pass
    return {
        "cluster": text(cluster)[:160],
        "jobs_seen": len(running),
        "target": running[0]["jid"] if running else None,
        "log": state["log"] + ["triage: %d running job(s), cluster says %s"
                               % (len(running), text(cluster)[:70])],
    }


def decide(state):
    """Choose the action from what was observed. This is the part that belongs in Burr, not in Aegis."""
    if not state["target"]:
        return {"action": "none", "log": state["log"] + ["decide: nothing is running, so nothing to do"]}
    return {"action": "stop_with_savepoint",
            "log": state["log"] + ["decide: stop job %s with a final savepoint" % state["target"]]}


def attempt_stop(state):
    """Try the destructive call with no ceremony. Expected to be refused, and that is the lesson."""
    if not state["target"]:
        return {"refusals": state["refusals"], "log": state["log"] + ["attempt: skipped, no target"]}
    r = call(os.environ["OPERATOR_TOKEN"], "flink_stop_job", stop_args(state))
    d = decision(r)
    return {"refusals": state["refusals"] + [{"tool": "flink_stop_job", "code": d.get("code"),
                                              "step": d.get("step"), "reason": d.get("reason")}],
            "log": state["log"] + ["attempt: refused %s at step %s" % (d.get("code"), d.get("step"))]}


def request_approval(state):
    """The dry run is the request. It validates against the real job and returns a receipt."""
    if not state["target"]:
        return {"approval_request": None, "receipt": None, "preview": "",
                "log": state["log"] + ["request: skipped, no target"]}
    r = call(os.environ["OPERATOR_TOKEN"], "flink_stop_job", stop_args(state), {DRY_RUN: True})
    d = decision(r)
    return {"approval_request": d.get("approvalRequestId"),
            "receipt": d.get("dryRunReceipt"),
            "preview": text(r)[:200],
            "log": state["log"] + ["request: dry run %s, approval request %s opened, receipt issued=%s"
                                   % (d.get("outcome") or d.get("code"), d.get("approvalRequestId"),
                                      bool(d.get("dryRunReceipt")))]}


def wait_for_human(state):
    """Separation of duties. The agent's own operator identity cannot approve the agent's request.

    In production nothing here is automated: a person opens the approval in a UI and decides. The
    approver token is used in this example only so the flow can run end to end unattended, and the
    self-approval attempt just above it is what proves the control is real.
    """
    rid = state["approval_request"]
    if not rid:
        return {"approved": False, "approval_token": None,
                "log": state["log"] + ["wait: no approval request to approve"]}
    mine, _ = admin(os.environ["OPERATOR_TOKEN"], "/approvals/%s/approve" % rid, "POST")
    theirs, _ = admin(os.environ["APPROVER_TOKEN"], "/approvals/%s/approve" % rid, "POST")
    _, issued = admin(os.environ["OPERATOR_TOKEN"], "/approvals/%s" % rid)
    return {"approved": theirs == 200,
            "approval_token": issued.get("token"),
            "log": state["log"] + ["wait: the agent approving its own request -> HTTP %s; a human approver -> HTTP %s"
                                   % (mine, theirs)]}


def promote(state):
    """Retry with both the approval and the receipt. Either one alone is not enough."""
    if not state["approval_token"]:
        return {"done": False, "result": "", "refusals": state["refusals"],
                "log": state["log"] + ["promote: skipped, no approval token"]}
    token = os.environ["OPERATOR_TOKEN"]
    half = call(token, "flink_stop_job", stop_args(state), {APPROVAL: state["approval_token"]})
    hd = decision(half)
    full = call(token, "flink_stop_job", stop_args(state),
                {APPROVAL: state["approval_token"], RECEIPT: state["receipt"]})
    fd = decision(full)
    allowed = fd.get("outcome") == "ALLOWED" or (not fd.get("code") and "result" in full)
    log = state["log"] + ["promote: approval alone -> %s at step %s" % (hd.get("code"), hd.get("step")),
                          "promote: approval and receipt -> %s"
                          % ("ALLOWED, " + text(full)[:60] if allowed else fd.get("code"))]
    return {"done": allowed,
            "result": text(full)[:200],
            "refusals": state["refusals"] + [{"tool": "flink_stop_job", "code": hd.get("code"),
                                              "step": hd.get("step"),
                                              "reason": "an approval without a dry-run receipt"}],
            "log": log}


def confirm(state):
    """Find out whether the savepoint actually worked, which is not what "allowed" means.

    This step exists because of a trap that is easy to fall into and expensive to discover late.
    Aegis answering ALLOWED means governance permitted the call and Flink accepted it. Flink's stop is
    asynchronous, so all it returned was a request id. Reading the status then looks conclusive:

        {"status": {"id": "COMPLETED"}, "operation": {...}}

    but `status` is the state of the *operation tracking*, not the outcome. A savepoint that failed also
    reports COMPLETED. The outcome is in `operation`: `location` when it worked, `failure-cause` when it
    did not. An agent that stops at `status.id` will report "savepoint COMPLETED" for a job that is
    still running with no savepoint taken, which is the worst kind of wrong: confidently.

    So this step reads the real outcome and then checks the job state as well.
    """
    token = os.environ["OPERATOR_TOKEN"]
    trigger = ""
    try:
        trigger = json.loads(state["result"] or "{}").get("request-id", "")
    except ValueError:
        pass
    if not trigger:
        return {"confirmed": False, "savepoint": "",
                "log": state["log"] + ["confirm: no request id to follow"]}
    outcome, note = "", ""
    for _ in range(20):
        time.sleep(2)
        r = call(token, "flink_get_savepoint_status", {"jobId": state["target"], "triggerId": trigger})
        try:
            body = json.loads(text(r) or "{}")
        except ValueError:
            continue
        if body.get("status", {}).get("id") != "COMPLETED":
            continue
        op = body.get("operation", {}) or {}
        if "location" in op:
            outcome, note = "succeeded", op["location"]
        else:
            cause = json.dumps(op.get("failure-cause", {}))
            outcome, note = "failed", cause[:200]
        break
    jobs = call(token, "flink_list_jobs")
    state_now = "unknown"
    try:
        for j in json.loads(text(jobs) or "[]"):
            if j.get("jid") == state["target"]:
                state_now = j.get("state")
    except ValueError:
        pass
    return {"confirmed": outcome == "succeeded",
            "savepoint": note,
            "log": state["log"] + ["confirm: status COMPLETED, operation says the savepoint %s" % (outcome or "is still running"),
                                   "confirm: the job is now %s" % state_now]}


def report(state):
    """What the agent did, and every refusal, in the words the gateway used."""
    lines = ["", "=" * 78, "Burr agent report: one incident, governed end to end", "=" * 78]
    lines += ["  " + entry for entry in state["log"]]
    refusals = [r for r in state["refusals"] if r.get("code")]
    if refusals:
        lines += ["", "Refused along the way, each with the gateway's own code and step:"]
        for r in refusals:
            lines.append("  %-16s %-20s step %-4s %s"
                         % (r["tool"], r["code"], r["step"], (r.get("reason") or "")[:60]))
    if state.get("confirmed"):
        outcome = "the job was stopped and the savepoint is at %s" % state.get("savepoint", "")
    elif state.get("done"):
        outcome = ("the call was ALLOWED but the savepoint did NOT succeed; the job was not stopped. "
                   "Cause: %s" % state.get("savepoint", "unknown"))
    else:
        outcome = "no action was taken"
    lines += ["", "Outcome: %s" % outcome,
              "",
              "The agent chose the steps. The gateway decided whether each one was allowed. Neither of",
              "them decided whether the backend then succeeded, which is why the last step reads the",
              "real outcome instead of trusting ALLOWED.", ""]
    return {"report": "\n".join(lines), "log": state["log"]}


# Per-action write keys. Burr validates that an action produces exactly what it declares, which is a
# useful discipline: it is the same idea as Aegis refusing an undeclared tool argument.
WRITES = {
    "triage": ["cluster", "jobs_seen", "target", "log"],
    "decide": ["action", "log"],
    "attempt_stop": ["refusals", "log"],
    "request_approval": ["approval_request", "receipt", "preview", "log"],
    "wait_for_human": ["approved", "approval_token", "log"],
    "promote": ["done", "result", "refusals", "log"],
    "confirm": ["confirmed", "savepoint", "log"],
    "report": ["report", "log"],
}

STEPS = [triage, decide, attempt_stop, request_approval, wait_for_human, promote, confirm, report]

INITIAL = {"log": [], "refusals": [], "target": None, "action": None, "approval_request": None,
           "receipt": None, "approval_token": None, "approved": False, "done": False,
           "cluster": "", "jobs_seen": 0, "preview": "", "result": "", "report": "",
           "confirmed": False, "savepoint": ""}


# ---------------------------------------------------------------------------- run as a Burr graph
def run_with_burr():
    """The same steps as a real Burr application, so the tracker records every governed decision."""
    from burr.core import ApplicationBuilder, State, action

    reads = sorted(INITIAL)

    def wrap(fn):
        @action(reads=reads, writes=WRITES[fn.__name__])
        def step(state: State) -> State:
            return state.update(**fn({k: state.get(k) for k in reads}))

        step.__name__ = fn.__name__
        return step

    actions = {fn.__name__: wrap(fn) for fn in STEPS}
    builder = ApplicationBuilder().with_actions(**actions).with_entrypoint("triage")
    # A linear graph here on purpose: the branching that matters in this example is inside the
    # governance chain, not inside the agent.
    transitions = [(STEPS[i].__name__, STEPS[i + 1].__name__) for i in range(len(STEPS) - 1)]
    builder = builder.with_transitions(*transitions).with_state(State(INITIAL))
    try:
        builder = builder.with_tracker("local", project="aegis-governed-agent")
    except Exception:
        pass  # tracking is a convenience, not a requirement
    app = builder.build()
    _, _, state = app.run(halt_after=["report"])
    return state.get("report")


def run_without_burr():
    """Identical steps, no framework, so the governance flow is visible with the standard library."""
    state = dict(INITIAL)
    for fn in STEPS:
        state.update(fn(state))
    return state["report"]


def main():
    for var in ("OPERATOR_TOKEN", "APPROVER_TOKEN"):
        if not os.environ.get(var):
            print("set %s to the token the gateway was started with" % var, file=sys.stderr)
            return 2
    try:
        import burr.core  # noqa: F401
        print("running the graph as an Apache Burr application", flush=True)
        report = run_with_burr()
    except ImportError:
        print("burr is not installed; running the same steps in sequence "
              "(pip install burr to see them tracked)", flush=True)
        report = run_without_burr()
    print(report, flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
