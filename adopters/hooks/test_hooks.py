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
"""Unit tests for the reference hooks: python -m unittest test_hooks (from this directory)."""

import datetime as dt
import json
import os
import socket
import subprocess
import sys
import time
import unittest
import urllib.error
import urllib.request

import change_window
import hookserver
import pii_guard
import secrets_guard

HERE = os.path.dirname(os.path.abspath(__file__))


def req(phase="before", tool_class="READ", arguments=None, result=None, dry_run=False):
    r = {"aegis": "2.0", "phase": phase, "requestId": "r1", "toolClass": tool_class, "dryRun": dry_run,
         "caller": {"actor": "ana", "groups": ["analysts"]}}
    if arguments is not None:
        r["arguments"] = arguments
    if result is not None:
        r["result"] = result
    return r


class PiiGuardTest(unittest.TestCase):
    def test_cards_need_issuer_prefix_and_luhn(self):
        self.assertEqual(pii_guard.finding("pay with 4111 1111 1111 1111 today"), "a payment card number")
        self.assertEqual(pii_guard.finding("5555-5555-5555-4444"), "a payment card number")
        self.assertIsNone(pii_guard.finding("4111 1111 1111 1112"), "fails Luhn")
        self.assertIsNone(pii_guard.finding("event at 1696000000000 ms"), "a timestamp is not a card")

    def test_iban_and_ssn(self):
        self.assertEqual(pii_guard.finding("send to GB82 WEST 1234 5698 7654 32"), "an IBAN")
        self.assertIsNone(pii_guard.finding("GB82 WEST 1234 5698 7654 33"), "fails mod 97")
        self.assertEqual(pii_guard.finding("ssn 123-45-6789"), "a US Social Security number")
        self.assertIsNone(pii_guard.finding("ssn 000-45-6789"), "never issued")
        self.assertIsNone(pii_guard.finding("released 2026-10-03"))

    def test_decide_names_the_path_never_the_value(self):
        d = pii_guard.decide(req(arguments={"order": {"notes": ["ok", "card 4111111111111111"]}}))
        self.assertFalse(d["allow"])
        self.assertIn("/order/notes/1", d["reason"])
        self.assertNotIn("4111", d["reason"])
        self.assertTrue(pii_guard.decide(req(arguments={"topic": "orders"}))["allow"])

    def test_without_the_data_it_denies(self):
        self.assertFalse(pii_guard.decide(req())["allow"])
        self.assertFalse(pii_guard.decide(req(phase="afterTool"))["allow"])
        after = req(phase="afterTool", result={"content": [{"type": "text", "text": "IBAN DE89370400440532013000"}]})
        self.assertFalse(pii_guard.decide(after)["allow"])


class SecretsGuardTest(unittest.TestCase):
    def test_known_shapes(self):
        aws = "AKIA" + "IOSFODNN7EXAMPLE"
        self.assertEqual(secrets_guard.finding("key " + aws), "an AWS access key id")
        self.assertEqual(secrets_guard.finding("ghp_" + "a" * 36), "a GitHub token")
        self.assertEqual(secrets_guard.finding("-----BEGIN " + "RSA PRIVATE KEY-----"), "a private key")
        jwt = "eyJhbGciOi" + "JIUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJlLXZhbHVl"
        self.assertEqual(secrets_guard.finding("Bearer " + jwt), "a JSON Web Token")
        invalid_jwt = "eyJub3RhbGciOjF9" + ".eyJzdWIiOiJ4In0.c2lnbmF0dXJl"
        self.assertIsNone(secrets_guard.finding(invalid_jwt), "header without alg")
        self.assertIsNone(secrets_guard.finding("plain words and AKIA short"))

    def test_keys_are_checked_too_and_after_phase_passes(self):
        aws = "AKIA" + "IOSFODNN7EXAMPLE"
        d = secrets_guard.decide(req(arguments={aws: 1}))
        self.assertFalse(d["allow"])
        self.assertNotIn(aws, d["reason"])
        self.assertTrue(secrets_guard.decide(req(phase="afterTool"))["allow"])
        self.assertFalse(secrets_guard.decide(req())["allow"], "no arguments sent: cannot check, so deny")


class ChangeWindowTest(unittest.TestCase):
    def window(self, now, freezes=()):
        return change_window.Window(["Mon", "Tue", "Wed", "Thu", "Fri"], dt.time(9), dt.time(17),
                                    dt.timezone.utc, list(freezes), clock=lambda: now)

    def test_reads_and_dry_runs_always_pass(self):
        sunday = dt.datetime(2026, 10, 4, 12, tzinfo=dt.timezone.utc)
        w = self.window(sunday)
        self.assertTrue(w.decide(req(tool_class="READ"))["allow"])
        self.assertTrue(w.decide(req(tool_class="DESTRUCTIVE", dry_run=True))["allow"])
        self.assertFalse(w.decide(req(tool_class="MUTATE"))["allow"])

    def test_hours_days_and_freezes(self):
        friday_noon = dt.datetime(2026, 10, 2, 12, tzinfo=dt.timezone.utc)
        self.assertTrue(self.window(friday_noon).decide(req(tool_class="MUTATE"))["allow"])
        late = dt.datetime(2026, 10, 2, 17, tzinfo=dt.timezone.utc)
        self.assertIn("open 09:00 to 17:00", self.window(late).decide(req(tool_class="MUTATE"))["reason"])
        frozen = self.window(friday_noon, [(dt.date(2026, 10, 1), dt.date(2026, 10, 5))])
        self.assertIn("freeze", frozen.decide(req(tool_class="DESTRUCTIVE"))["reason"])

    def test_offset_parsing(self):
        tz = change_window.offset("+05:30")
        self.assertEqual(tz.utcoffset(None), dt.timedelta(hours=5, minutes=30))
        with self.assertRaises(SystemExit):
            change_window.offset("IST")


class OverHttpTest(unittest.TestCase):
    """The real listener: status codes Aegis relies on to fail closed."""

    @classmethod
    def setUpClass(cls):
        s = socket.socket()
        s.bind(("127.0.0.1", 0))
        cls.port = s.getsockname()[1]
        s.close()
        env = dict(os.environ, AEGIS_HOOK_TOKEN="hook-token-0123456789")
        cls.proc = subprocess.Popen([sys.executable, os.path.join(HERE, "pii_guard.py"), "--port", str(cls.port)],
                                    env=env, stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/healthz" % cls.port, timeout=1)
                return
            except OSError:
                time.sleep(0.1)
        raise RuntimeError("hook did not start")

    @classmethod
    def tearDownClass(cls):
        cls.proc.terminate()
        cls.proc.wait(10)

    def post(self, body, token="hook-token-0123456789"):
        data = body if isinstance(body, bytes) else json.dumps(body).encode()
        r = urllib.request.Request("http://127.0.0.1:%d/" % self.port, data=data,
                                   headers={"Content-Type": "application/json", "Authorization": "Bearer " + token})
        try:
            with urllib.request.urlopen(r, timeout=5) as resp:
                return resp.status, json.loads(resp.read())
        except urllib.error.HTTPError as e:
            return e.code, None

    def test_contract(self):
        self.assertEqual(self.post(req(arguments={"q": "fine"})), (200, {"allow": True}))
        status, body = self.post(req(arguments={"q": "4111111111111111"}))
        self.assertEqual(status, 200)
        self.assertEqual(body["code"], "POLICY_DENIED")
        self.assertEqual(self.post(req(arguments={}), token="wrong")[0], 401)
        self.assertEqual(self.post(b"not json")[0], 400)
        self.assertEqual(self.post({"aegis": "1.0"})[0], 400)


class HelpersTest(unittest.TestCase):
    def test_deny_codes_are_limited(self):
        self.assertEqual(hookserver.deny("x", code="MADE_UP")["code"], "POLICY_DENIED")
        self.assertEqual(hookserver.deny("x", code="INVALID_INPUT")["code"], "INVALID_INPUT")

    def test_strings_walks_keys_values_and_bounds_depth(self):
        found = dict(hookserver.strings({"a/b": ["x", {"c": "y"}]}))
        self.assertEqual(found["/a~1b/0"], "x")
        self.assertEqual(found["/a~1b/1/c"], "y")
        deep = "v"
        for _ in range(70):
            deep = [deep]
        with self.assertRaises(ValueError):
            list(hookserver.strings(deep))


if __name__ == "__main__":
    unittest.main()
