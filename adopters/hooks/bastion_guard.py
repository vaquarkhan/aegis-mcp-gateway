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
"""Reference hook: delegates prompt-injection and PII detection to MCP-Bastion's analyzers.

This is the "call it, do not rebuild it" path. Aegis keeps the controls it is strong at (approval,
fail-closed audit, catalog pinning, scope) and consults MCP-Bastion out of process for the two
model-based checks it is strong at:

  prompt injection  -> mcp_bastion.pillars.prompt_guard.PromptGuardEngine.is_malicious(text)
  personal data     -> mcp_bastion.pillars.pii_redaction.PIIRedactor.detect_spans(text)

MCP-Bastion is a SEPARATE project with its own license (Source Available, not Apache-2.0). It is an
optional runtime dependency: the operator installs it themselves. This file links to it only by a
loose, out-of-process import, so the Aegis distribution stays Apache-2.0 and does not embed it.

    pip install mcp-bastion-python          # PromptGuard heuristics work offline (model optional)
    pip install mcp-bastion-python[ml]      # adds Presidio so PII detection is available

Before phase: needs settings.includeArguments: true. After phases: needs settings.includeResult: true.
Without the data it was asked to check, it denies; it does not guess. A reason names where a match was
found and the kind, never the value, because the reason goes to the audit journal and the caller.

Usage: python bastion_guard.py --port 18304   (add --no-injection or --no-pii to run one check only)
"""

import sys

import hookserver

try:
    from mcp_bastion.pillars.prompt_guard import PromptGuardEngine
    from mcp_bastion.pillars.pii_redaction import PIIRedactor
    _IMPORT_ERROR = None
except Exception as exc:  # the package, or an extra such as Presidio, is not installed
    PromptGuardEngine = None
    PIIRedactor = None
    _IMPORT_ERROR = exc

_INJECTION = None
_PII = None


def _pii_entities(text):
    """The entity types found in text (for the reason), never the matched text itself."""
    found = set()
    for span in _PII.detect_spans(text):
        found.add(getattr(span, "entity_type", None) or getattr(span, "label", None) or "PII")
    return sorted(found)


def decide(req):
    phase = req.get("phase")
    if phase == "before":
        if "arguments" not in req:
            return hookserver.deny("bastion-guard needs settings.includeArguments: true to check arguments")
        scope, value = "argument", req["arguments"]
    else:
        if "result" not in req:
            return hookserver.deny("bastion-guard needs settings.includeResult: true to check results")
        scope, value = "result", req["result"]
    for where, text in hookserver.strings(value):
        if _INJECTION is not None and _INJECTION.is_malicious(text):
            return hookserver.deny("%s at %s matches a prompt-injection pattern" % (scope, where),
                                   code="PROMPT_INJECTION")
        if _PII is not None:
            entities = _pii_entities(text)
            if entities:
                return hookserver.deny("%s at %s contains personal data (%s)"
                                       % (scope, where, ", ".join(entities)))
    return hookserver.allow()


def main():
    global _INJECTION, _PII
    ap = hookserver.parser(__doc__)
    ap.add_argument("--no-injection", action="store_true", help="do not run the PromptGuard injection check")
    ap.add_argument("--no-pii", action="store_true", help="do not run the Presidio PII check")
    ap.add_argument("--threshold", type=float, default=None, help="PromptGuard malicious threshold (0..1)")
    args = ap.parse_args()

    want_injection = not args.no_injection
    want_pii = not args.no_pii
    if not (want_injection or want_pii):
        sys.stderr.write("bastion-guard: nothing to do; enable at least one of injection or pii\n")
        sys.exit(2)
    if _IMPORT_ERROR is not None:
        sys.stderr.write("bastion-guard: MCP-Bastion is not importable (%s: %s).\n"
                         "Install it first: pip install mcp-bastion-python[ml]\n"
                         % (type(_IMPORT_ERROR).__name__, _IMPORT_ERROR))
        sys.exit(2)
    # Build only the engines that are enabled. A check that cannot be built is a hard start failure,
    # so the hook never silently fails open on a control the operator asked for.
    if want_injection:
        kwargs = {} if args.threshold is None else {"threshold": args.threshold}
        _INJECTION = PromptGuardEngine(**kwargs)
    if want_pii:
        _PII = PIIRedactor()
    sys.stderr.write("bastion-guard: injection=%s pii=%s\n" % (want_injection, want_pii))
    hookserver.serve("aegis-bastion-guard", decide, args)


if __name__ == "__main__":
    main()
