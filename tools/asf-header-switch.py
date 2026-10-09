#!/usr/bin/env python3
#
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
#
"""Switch author Apache-2.0 source headers to the applicable standard ASF source header.

Run this only after the ASF has recorded the applicable Software Grant for this code. The gate concerns
the header's legal applicability, not whether Aegis is an Apache Incubator project. Before the grant is
recorded, the ASF header would state something that is not yet verified, so the default mode is a dry run
that lists what would change.

    python tools/asf-header-switch.py                 # dry run: count and list files
    python tools/asf-header-switch.py --apply --sga-recorded

The header keeps each file's comment style: the comment prefix on the copyright line (" * ", "# ",
two spaces inside an HTML comment, and so on) is reused for every line of the ASF header. Files are
written back with their original line endings. Run `mvn -B clean verify` afterwards; Apache RAT checks
every header. Moving copyright statements into NOTICE is a separate, reviewed step.
"""

import argparse
import io
import os
import sys

OLD_MARKER = "Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors"
END_MARKER = "limitations under the License."
ASF_HEADER = [
    "Licensed to the Apache Software Foundation (ASF) under one",
    "or more contributor license agreements.  See the NOTICE file",
    "distributed with this work for additional information",
    "regarding copyright ownership.  The ASF licenses this file",
    "to you under the Apache License, Version 2.0 (the",
    "\"License\"); you may not use this file except in compliance",
    "with the License.  You may obtain a copy of the License at",
    "",
    "  http://www.apache.org/licenses/LICENSE-2.0",
    "",
    "Unless required by applicable law or agreed to in writing,",
    "software distributed under the License is distributed on an",
    "\"AS IS\" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY",
    "KIND, either express or implied.  See the License for the",
    "specific language governing permissions and limitations",
    "under the License.",
]
SKIP_DIRS = {".git", "target", "node_modules", ".idea", ".vscode"}
TEXT_SUFFIXES = (".java", ".xml", ".md", ".html", ".css", ".js", ".mjs", ".py", ".yaml", ".yml",
                 ".properties", ".svg", ".txt", ".sh", ".ps1", ".mmd", ".json")


def switch(text):
    """Returns the text with the header replaced, or None when the file has no author header."""
    newline = "\r\n" if "\r\n" in text else "\n"
    lines = text.replace("\r\n", "\n").split("\n")
    for i, line in enumerate(lines):
        at = line.find(OLD_MARKER)
        if at < 0:
            continue
        prefix = line[:at]
        for j in range(i, min(i + 30, len(lines))):
            if END_MARKER in lines[j]:
                header = [(prefix + h).rstrip() if h else prefix.rstrip() for h in ASF_HEADER]
                return newline.join(lines[:i] + header + lines[j + 1:])
        return None
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
    parser.add_argument("--apply", action="store_true", help="write the changes")
    parser.add_argument("--sga-recorded", action="store_true",
                        help="confirm the ASF has recorded the Software Grant for this code")
    args = parser.parse_args()
    if args.apply and not args.sga_recorded:
        print("refusing to apply: pass --sga-recorded once the ASF has recorded the Software Grant", file=sys.stderr)
        return 2
    root = os.path.abspath(args.root)
    changed = []
    for directory, dirs, files in os.walk(root):
        dirs[:] = [d for d in dirs if d not in SKIP_DIRS]
        for name in files:
            if not name.endswith(TEXT_SUFFIXES):
                continue
            path = os.path.join(directory, name)
            try:
                text = io.open(path, encoding="utf-8", newline="").read()
            except (UnicodeDecodeError, OSError):
                continue
            updated = switch(text)
            if updated is None or updated == text:
                continue
            changed.append(os.path.relpath(path, root))
            if args.apply:
                io.open(path, "w", encoding="utf-8", newline="").write(updated)
    for p in changed:
        print(p)
    print(("switched " if args.apply else "would switch ") + str(len(changed)) + " files", file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
