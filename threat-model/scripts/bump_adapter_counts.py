from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
#!/usr/bin/env python3
"""Synchronize the adapter-count prose for the AsterixDB and SkyWalking additions.

25 -> 27 deployable adapter configs, 116 -> 123 adapter tools. Precise phrase replacements only,
so unrelated numbers are never touched. Re-runnable: counts how many it changed per file.
"""
import io
import os

ROOT = str(_REPO)

# (relative path, [(old, new), ...])
COMMON = [
    ("116 tools", "123 tools"),
    ("25 deployable adapter", "27 deployable adapter"),
    ("25 deployable adopter", "27 deployable adopter"),
    ("25 shipped configurations", "27 shipped configurations"),
]

FILES = {
    "README.md": COMMON + [
        ("**4 configuration/contract-supported profiles** that still need a full product stack before a real Keep run (Atlas, Cassandra, Impala, Paimon)",
         "**6 configuration/contract-supported profiles** that still need a full product stack before a real Keep run (Atlas, Cassandra, Impala, Paimon, AsterixDB, SkyWalking)"),
    ],
    "docs/ADOPTION-GUIDE.md": COMMON,
    "docs/FEATURES.md": COMMON,
    "docs/VALIDATION.md": COMMON + [
        ("The other **4** shipped profiles (Atlas, Cassandra, Impala, Paimon) are config/contract",
         "The other **6** shipped profiles (Atlas, Cassandra, Impala, Paimon, AsterixDB, SkyWalking) are config/contract"),
    ],
    "docs/COVERAGE-0.1-VS-2.0.md": COMMON,
    "adopters/configurations/adapters/README.md": COMMON,
    "docs/HANDBOOK.md": COMMON,
    "docs/GOVERNANCE-INFORMATION-AND-EXTENSIBILITY.md": COMMON,
    "docs/evidence/CLAIMS-MATRIX.md": COMMON,
    "docs/DESIGN-REVIEW-AND-GAPS.md": COMMON,
    "threat-model/APACHE-MCP-LANDSCAPE.md": [("116 MCP tools", "123 MCP tools"),
                                             ("25 deployable adapter", "27 deployable adapter")],
}

for rel, subs in FILES.items():
    path = os.path.join(ROOT, rel.replace("/", os.sep))
    if not os.path.isfile(path):
        print(f"MISSING {rel}")
        continue
    with io.open(path, "r", encoding="utf-8", newline="") as f:
        text = f.read()
    changed = 0
    for old, new in subs:
        n = text.count(old)
        if n:
            text = text.replace(old, new)
            changed += n
    with io.open(path, "w", encoding="utf-8", newline="") as f:
        f.write(text)
    print(f"{rel}: {changed} replacement(s)")
