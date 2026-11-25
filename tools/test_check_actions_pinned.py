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
"""Tests for check-actions-pinned.py. Run: python3 -m unittest -v tools/test_check_actions_pinned.py"""
import importlib.util
import tempfile
import unittest
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "check_actions_pinned", Path(__file__).with_name("check-actions-pinned.py"))
checker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(checker)

SHA = "a" * 40


class CheckActionsPinned(unittest.TestCase):
    def bad(self, text):
        return [ref for _, ref in checker.violations(text)]

    def test_pinned_sha_with_comment_passes(self):
        self.assertEqual([], self.bad(f"- uses: actions/checkout@{SHA} # v1\n"))

    def test_tag_and_branch_refs_fail(self):
        self.assertEqual(["actions/checkout@v4"], self.bad("      - uses: actions/checkout@v4\n"))
        self.assertEqual(["a/b@main"], self.bad("    uses: a/b@main\n"))

    def test_short_or_uppercase_sha_fails(self):
        self.assertEqual(1, len(self.bad("- uses: a/b@" + "a" * 39 + "\n")))
        self.assertEqual(1, len(self.bad("- uses: a/b@" + "A" * 40 + "\n")))

    def test_missing_ref_fails(self):
        self.assertEqual(["actions/checkout"], self.bad("- uses: actions/checkout\n"))

    def test_quoted_reference_is_checked(self):
        self.assertEqual(["a/b@v1"], self.bad("- uses: 'a/b@v1'\n"))

    def test_local_action_allowed(self):
        self.assertEqual([], self.bad("- uses: ./.github/actions/x\n"))

    def test_docker_requires_digest(self):
        self.assertEqual(["docker://alpine:3"], self.bad("- uses: docker://alpine:3\n"))
        self.assertEqual([], self.bad("- uses: docker://alpine@sha256:" + "b" * 64 + "\n"))

    def test_run_text_mentioning_uses_is_ignored(self):
        self.assertEqual([], self.bad("        run: echo uses: foo@v1\n"))

    def test_main_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            wf = Path(d)
            (wf / "ok.yml").write_text(f"- uses: a/b@{SHA}\n")
            self.assertEqual(0, checker.main([d]))
            (wf / "bad.yaml").write_text("- uses: a/b@v1\n")
            self.assertEqual(1, checker.main([d]))
            self.assertEqual(2, checker.main([str(wf / "missing")]))


if __name__ == "__main__":
    unittest.main()
