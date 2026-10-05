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
"""Reference hook: refuses calls that would carry a credential to a backend.

Aegis already redacts known secret shapes in tool output. This hook covers the other direction: an
agent that pastes a credential into a tool argument would hand it to the backend, its logs and anyone
who can read them. Detects:
  AWS access key ids, GitHub tokens, Slack tokens, Google API keys, PEM private key blocks,
  and JSON Web Tokens (three base64url parts, the first decoding to a JSON header with "alg")

Before phase, with includeArguments: true. Usage: python secrets_guard.py --port 18302
"""

import base64
import json
import re

import hookserver

PATTERNS = [
    ("an AWS access key id", re.compile(r"\b(?:AKIA|ASIA)[0-9A-Z]{16}\b")),
    ("a GitHub token", re.compile(r"\bgh[pousr]_[A-Za-z0-9]{36,255}\b")),
    ("a Slack token", re.compile(r"\bxox[abprs]-[A-Za-z0-9-]{10,}\b")),
    ("a Google API key", re.compile(r"\bAIza[0-9A-Za-z_-]{35}\b")),
    ("a private key", re.compile(r"-----BEGIN (?:[A-Z]+ )?PRIVATE KEY-----")),
]
JWT = re.compile(r"\b(eyJ[A-Za-z0-9_-]{8,})\.([A-Za-z0-9_-]{8,})\.([A-Za-z0-9_-]{8,})\b")


def is_jwt_header(part):
    try:
        header = json.loads(base64.urlsafe_b64decode(part + "=" * (-len(part) % 4)))
    except (ValueError, UnicodeDecodeError):
        return False
    return isinstance(header, dict) and "alg" in header


def finding(text):
    for label, pattern in PATTERNS:
        if pattern.search(text):
            return label
    for m in JWT.finditer(text):
        if is_jwt_header(m.group(1)):
            return "a JSON Web Token"
    return None


def decide(req):
    if req.get("phase") != "before":
        return hookserver.allow()  # output redaction is the gateway's own output guard
    if "arguments" not in req:
        return hookserver.deny("secrets-guard needs settings.includeArguments: true to check arguments")
    for where, text in hookserver.strings(req["arguments"]):
        what = finding(text)
        if what:
            return hookserver.deny("argument at %s contains %s" % (where, what))
    return hookserver.allow()


if __name__ == "__main__":
    hookserver.serve("aegis-secrets-guard", decide, hookserver.parser(__doc__).parse_args())
