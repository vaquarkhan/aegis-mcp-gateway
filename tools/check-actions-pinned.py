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
"""Fails when a workflow step uses an action that is not pinned to a full 40-hex commit SHA.

ASF infrastructure-actions policy: third-party actions must be pinned to an immutable commit.
Local actions (./path) are allowed; docker:// references must carry a sha256 digest.
Usage: python3 tools/check-actions-pinned.py [workflow-dir ...]   (default: .github/workflows)
"""
import re
import sys
from pathlib import Path

USES = re.compile(r"^\s*(?:-\s*)?uses:\s*(\S+)")
SHA = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")
DIGEST = re.compile(r"^docker://\S+@sha256:[0-9a-f]{64}$")


def violations(text):
    """Yield (line number, reference) for each unpinned `uses:` in the workflow text."""
    for number, line in enumerate(text.splitlines(), 1):
        match = USES.match(line)
        if not match:
            continue
        ref = match.group(1).strip("'\"")
        if ref.startswith("./"):
            continue
        if ref.startswith("docker://"):
            if not DIGEST.match(ref):
                yield number, ref
        elif not SHA.match(ref):
            yield number, ref


def main(argv):
    roots = [Path(a) for a in argv] or [Path(".github/workflows")]
    files = []
    for root in roots:
        if not root.is_dir():
            print(f"not a directory: {root}", file=sys.stderr)
            return 2
        files += sorted(list(root.glob("*.yml")) + list(root.glob("*.yaml")))
    bad = 0
    for path in files:
        for number, ref in violations(path.read_text(encoding="utf-8")):
            print(f"{path}:{number}: not pinned to a full commit SHA: {ref}")
            bad += 1
    if bad:
        return 1
    print(f"all actions pinned ({len(files)} workflow files)")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
