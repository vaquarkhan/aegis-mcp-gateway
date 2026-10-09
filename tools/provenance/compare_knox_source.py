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
"""Compare Aegis production Java with a local Apache Knox source checkout.

This is a reproducible similarity check, not a legal provenance determination. It reports exact
substantive-line windows, exact Java-token windows, Knox imports outside the extension, and Knox
classes accidentally packaged in Aegis jars. It never sends source code over the network.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import sys
import zipfile
from collections import defaultdict
from pathlib import Path

JAVA_TOKEN = re.compile(
    r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|[A-Za-z_$][A-Za-z0-9_$]*|'
    r'0[xX][0-9A-Fa-f_]+|\d+(?:\.\d+)?|>>>|>>|<<|==|!=|<=|>=|&&|\|\||::|->|\+\+|--|'
    r'[{}()\[\];,.?:~!%^&*+\-/|<>=]'
)
COMMENT = re.compile(r'/\*.*?\*/|//[^\r\n]*', re.S)
SPACE = re.compile(r'\s+')
ONLY_PUNCTUATION = re.compile(r'^[{}();,]+$')


def production_java(root: Path) -> list[Path]:
    return sorted(
        p for p in root.rglob('*.java')
        if 'target' not in p.parts and 'src' in p.parts and 'main' in p.parts
    )


def source_body(path: Path) -> str:
    text = path.read_text(encoding='utf-8', errors='strict')
    text = COMMENT.sub(' ', text)
    kept = []
    for line in text.splitlines():
        stripped = line.strip()
        if stripped.startswith('package ') or stripped.startswith('import '):
            continue
        kept.append(line)
    return '\n'.join(kept)


def substantive_lines(path: Path) -> list[str]:
    lines = []
    for line in source_body(path).splitlines():
        line = SPACE.sub(' ', line.strip())
        if line and not ONLY_PUNCTUATION.fullmatch(line):
            lines.append(line)
    return lines


def java_tokens(path: Path) -> list[str]:
    return JAVA_TOKEN.findall(source_body(path))


def digest_window(values: list[str]) -> str:
    return hashlib.sha256('\x1f'.join(values).encode('utf-8')).hexdigest()


def exact_window_matches(aegis_files: list[Path], knox_files: list[Path], width: int,
                         extractor, roots: tuple[Path, Path]) -> list[dict[str, object]]:
    index: dict[str, list[tuple[Path, int, list[str]]]] = defaultdict(list)
    for path in aegis_files:
        values = extractor(path)
        for start in range(0, len(values) - width + 1):
            window = values[start:start + width]
            index[digest_window(window)].append((path, start + 1, window))

    matches = []
    seen = set()
    for path in knox_files:
        values = extractor(path)
        for start in range(0, len(values) - width + 1):
            window = values[start:start + width]
            for a_path, a_start, a_window in index.get(digest_window(window), ()):
                if window != a_window:
                    continue
                key = (str(a_path), a_start, str(path), start + 1)
                if key in seen:
                    continue
                seen.add(key)
                matches.append({
                    'aegisFile': a_path.relative_to(roots[0]).as_posix(),
                    'aegisStart': a_start,
                    'knoxFile': path.relative_to(roots[1]).as_posix(),
                    'knoxStart': start + 1,
                    'sample': ' '.join(window)[:500],
                })
    return matches


def source_tree_digest(root: Path, files: list[Path]) -> str:
    digest = hashlib.sha256()
    for path in files:
        digest.update(path.relative_to(root).as_posix().encode('utf-8'))
        digest.update(b'\x00')
        digest.update(path.read_bytes())
        digest.update(b'\x00')
    return 'sha256:' + digest.hexdigest()


def git_revision(root: Path) -> str | None:
    result = subprocess.run(
        ['git', '-C', str(root), 'rev-parse', 'HEAD'], capture_output=True, text=True, check=False
    )
    return result.stdout.strip() if result.returncode == 0 else None


def knox_import_violations(root: Path, files: list[Path]) -> list[str]:
    violations = []
    extension = root / 'aegis-knox3-extension'
    for path in files:
        if extension not in path.parents and 'org.apache.knox' in path.read_text(encoding='utf-8'):
            violations.append(path.relative_to(root).as_posix())
    return violations


def packaged_knox_classes(root: Path) -> list[dict[str, str]]:
    violations = []
    for jar in sorted(root.rglob('target/*.jar')):
        if jar.name.endswith(('-sources.jar', '-javadoc.jar', '-tests.jar')):
            continue
        try:
            with zipfile.ZipFile(jar) as archive:
                for name in archive.namelist():
                    if name.startswith('org/apache/knox/') and name.endswith('.class'):
                        violations.append({
                            'jar': jar.relative_to(root).as_posix(),
                            'entry': name,
                        })
        except zipfile.BadZipFile:
            violations.append({'jar': jar.relative_to(root).as_posix(), 'entry': '<invalid-jar>'})
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--aegis-root', type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument('--knox-root', type=Path, required=True)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--line-window', type=int, default=4)
    parser.add_argument('--token-window', type=int, default=60)
    args = parser.parse_args()

    aegis_root = args.aegis_root.resolve()
    knox_root = args.knox_root.resolve()
    aegis_files = production_java(aegis_root)
    knox_files = production_java(knox_root)
    if not aegis_files or not knox_files:
        parser.error('both roots must contain production Java below src/main')

    line_matches = exact_window_matches(
        aegis_files, knox_files, args.line_window, substantive_lines, (aegis_root, knox_root)
    )
    token_matches = exact_window_matches(
        aegis_files, knox_files, args.token_window, java_tokens, (aegis_root, knox_root)
    )
    report = {
        'schemaVersion': 1,
        'generatedAtUtc': dt.datetime.now(dt.timezone.utc).replace(microsecond=0).isoformat(),
        'method': {
            'scope': 'production Java files under src/main; comments, package and import lines excluded',
            'lineWindow': args.line_window,
            'tokenWindow': args.token_window,
            'matching': 'exact SHA-256-indexed windows followed by exact-value verification',
            'limitations': [
                'No similarity scan proves authorship, originality, ownership, or license compliance.',
                'This check does not detect independently rewritten logic, renamed identifiers, or translated code.',
                'Short conventional API implementations can match without copying.',
            ],
        },
        'inputs': {
            'aegisRoot': aegis_root.name,
            'aegisRevision': git_revision(aegis_root),
            'aegisSourceTreeDigest': source_tree_digest(aegis_root, aegis_files),
            'aegisJavaFiles': len(aegis_files),
            'knoxRoot': knox_root.name,
            'knoxRevision': git_revision(knox_root),
            'knoxSourceTreeDigest': source_tree_digest(knox_root, knox_files),
            'knoxJavaFiles': len(knox_files),
        },
        'results': {
            'exactSubstantiveLineMatches': line_matches,
            'exactJavaTokenMatches': token_matches,
            'knoxImportsOutsideExtension': knox_import_violations(aegis_root, aegis_files),
            'packagedKnoxClasses': packaged_knox_classes(aegis_root),
        },
    }
    encoded = json.dumps(report, indent=2, sort_keys=True) + '\n'
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(encoded, encoding='utf-8')
    else:
        sys.stdout.write(encoded)

    results = report['results']
    blocking = (
        results['exactJavaTokenMatches']
        or results['knoxImportsOutsideExtension']
        or results['packagedKnoxClasses']
    )
    print(
        f"Compared {len(aegis_files)} Aegis files with {len(knox_files)} Knox files: "
        f"line={len(line_matches)} (review), token={len(token_matches)} (blocking), "
        f"imports={len(results['knoxImportsOutsideExtension'])}, "
        f"packaged={len(results['packagedKnoxClasses'])}",
        file=sys.stderr,
    )
    # Short line matches are reported for human review because Java interfaces require conventional
    # method shapes (for example getRole()/getName() implementations). A 60-token exact match is long
    # enough to block this check pending review; so is any Knox import outside the extension or a Knox
    # class accidentally packaged in an Aegis jar.
    return 1 if blocking else 0


if __name__ == '__main__':
    raise SystemExit(main())
