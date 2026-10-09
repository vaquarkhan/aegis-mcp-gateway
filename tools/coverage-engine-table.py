#!/usr/bin/env python3
# Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
# SPDX-License-Identifier: Apache-2.0
"""Print a markdown side-by-side engine table for docs/COVERAGE-0.1-VS-2.0.md."""

from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
TSV = ROOT / "examples/aegis-examples/src/test/resources/aegis-0.1-tool-inventory.tsv"

# Mirrors Coverage01VsTwoTest.UNIMPLEMENTED_ENGINES keys
ABSENT_ENGINES = {"arrow", "beam", "hudi", "kudu", "ozone"}

# 2.0-only additions (not in 0.1 inventory)
ONLY_20 = {"burr": "10 tools (Apache Burr incubating)"}

tools = []
with TSV.open(encoding="utf-8") as f:
    for line in f:
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        eng, name, cls, *_ = line.split("\t")
        tools.append((eng, name, cls))

by_eng = Counter(t[0] for t in tools)
adapter_dirs = {p.name for p in (ROOT / "adopters/configurations/adapters").iterdir() if p.is_dir()}
shared = {p.parent.name for p in (ROOT / "adopters/configurations").glob("*/*.yaml") if p.parent.name != "adapters"}

print("| 0.1 engine | 0.1 tools | Aegis 2.0 deployable adapter | Notes |")
print("| --- | ---: | --- | --- |")
for eng in sorted(by_eng):
    deploy = "yes" if eng in adapter_dirs else ("shared profile" if eng in shared else "typed source / reference")
    note = ""
    if eng in ABSENT_ENGINES:
        note = "entire surface deliberately absent (no real HTTP API)"
    elif eng == "kafka":
        note = "plus 2 new Admin read tools in 2.0"
    print(f"| `{eng}` | {by_eng[eng]} | {deploy} | {note} |")
for eng, note in sorted(ONLY_20.items()):
    print(f"| `{eng}` | 0 | yes | 2.0-only: {note} |")
