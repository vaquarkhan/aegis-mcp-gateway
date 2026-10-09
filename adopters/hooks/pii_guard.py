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
"""Reference hook: refuses calls whose arguments, or results whose content, carry personal data.

Detects, with checksums so ordinary numbers do not trip it:
  payment card numbers (13 to 19 digits, spaces or dashes allowed, a card issuer prefix, Luhn check)
  IBANs (country code, check digits, ISO 7064 mod 97 check)
  US Social Security numbers (NNN-NN-NNNN, excluding the ranges never issued)

Before phase: needs includeArguments: true. After phases: needs includeResult: true. Without the data it
was asked to check, it denies; it does not guess. Usage: python pii_guard.py --port 18301
"""

import re

import hookserver

CARD = re.compile(r"(?<!\d)(?:\d[ -]?){12,18}\d(?!\d)")
# Issuer prefixes (Visa, Mastercard, American Express, Discover, JCB, Diners Club, UnionPay), so that
# timestamps and order numbers that happen to pass Luhn are not reported as cards.
CARD_PREFIX = re.compile(r"4|5[1-5]|2[2-7]|3[47]|6011|64[4-9]|65|35|30[0-5]|36|38|62")
IBAN = re.compile(r"\b[A-Z]{2}\d{2}(?: ?[A-Z0-9]){11,30}\b")
SSN = re.compile(r"(?<!\d)(?!000|666|9\d\d)\d{3}-(?!00)\d{2}-(?!0000)\d{4}(?!\d)")


def luhn(digits):
    total = 0
    for i, ch in enumerate(reversed(digits)):
        d = int(ch)
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        total += d
    return total % 10 == 0


def iban_ok(raw):
    s = raw.replace(" ", "")
    if not 15 <= len(s) <= 34:
        return False
    moved = s[4:] + s[:4]
    number = "".join(str(int(c, 36)) for c in moved)
    return int(number) % 97 == 1


def finding(text):
    for m in CARD.finditer(text):
        digits = re.sub(r"[ -]", "", m.group(0))
        if 13 <= len(digits) <= 19 and CARD_PREFIX.match(digits) and luhn(digits):
            return "a payment card number"
    for m in IBAN.finditer(text):
        if iban_ok(m.group(0)):
            return "an IBAN"
    if SSN.search(text):
        return "a US Social Security number"
    return None


def decide(req):
    phase = req.get("phase")
    if phase == "before":
        if "arguments" not in req:
            return hookserver.deny("pii-guard needs settings.includeArguments: true to check arguments")
        scope, value = "argument", req["arguments"]
    else:
        if "result" not in req:
            return hookserver.deny("pii-guard needs settings.includeResult: true to check results")
        scope, value = "result", req["result"]
    for where, text in hookserver.strings(value):
        what = finding(text)
        if what:
            return hookserver.deny("%s at %s contains %s" % (scope, where, what))
    return hookserver.allow()


if __name__ == "__main__":
    hookserver.serve("aegis-pii-guard", decide, hookserver.parser(__doc__).parse_args())
