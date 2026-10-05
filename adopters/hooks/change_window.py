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
"""Reference hook: MUTATE and DESTRUCTIVE calls only inside a change window, and never in a freeze.

READ calls and dry runs always pass: a dry run changes nothing, and it is how an operator prepares a
change before the window opens. The hook needs no arguments, so leave includeArguments off.

  --days Mon,Tue,Wed,Thu,Fri     weekdays the window is open (default Monday to Friday)
  --start 09:00 --end 17:00      open hours, end exclusive (default 09:00 to 17:00)
  --utc-offset +00:00            the clock the window is written in (default UTC)
  --freeze 2026-12-20:2027-01-04 a closed date range, inclusive; repeatable

Usage: python change_window.py --port 18303 --days Mon,Tue,Wed,Thu,Fri --start 09:00 --end 17:00
"""

import datetime as dt
import re

import hookserver

DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def offset(text):
    m = re.fullmatch(r"([+-])(\d{2}):(\d{2})", text)
    if not m:
        raise SystemExit("--utc-offset must look like +05:30 or -08:00")
    minutes = int(m.group(2)) * 60 + int(m.group(3))
    return dt.timezone(dt.timedelta(minutes=minutes if m.group(1) == "+" else -minutes))


def hhmm(text):
    return dt.datetime.strptime(text, "%H:%M").time()


def freeze(text):
    a, sep, b = text.partition(":")
    if not sep:
        raise SystemExit("--freeze must look like 2026-12-20:2027-01-04")
    return dt.date.fromisoformat(a), dt.date.fromisoformat(b)


class Window:
    def __init__(self, days, start, end, tz, freezes, clock=None):
        self.days, self.start, self.end, self.tz, self.freezes = days, start, end, tz, freezes
        self.clock = clock or (lambda: dt.datetime.now(dt.timezone.utc))

    def closed_because(self, now):
        local = now.astimezone(self.tz)
        for a, b in self.freezes:
            if a <= local.date() <= b:
                return "a change freeze is in effect (%s to %s)" % (a, b)
        if DAYS[local.weekday()] not in self.days:
            return "the change window is closed on %s" % DAYS[local.weekday()]
        if not self.start <= local.time() < self.end:
            return "the change window is open %s to %s" % (self.start.strftime("%H:%M"), self.end.strftime("%H:%M"))
        return None

    def decide(self, req):
        if req.get("toolClass") not in ("MUTATE", "DESTRUCTIVE") or req.get("dryRun") is True:
            return hookserver.allow()
        why = self.closed_because(self.clock())
        return hookserver.deny("%s call refused: %s" % (req.get("toolClass"), why)) if why else hookserver.allow()


def main():
    ap = hookserver.parser(__doc__)
    ap.add_argument("--days", default="Mon,Tue,Wed,Thu,Fri")
    ap.add_argument("--start", default="09:00")
    ap.add_argument("--end", default="17:00")
    ap.add_argument("--utc-offset", default="+00:00")
    ap.add_argument("--freeze", action="append", default=[])
    a = ap.parse_args()
    days = [d.strip() for d in a.days.split(",") if d.strip()] if a.days.strip() else []
    unknown = [d for d in days if d not in DAYS]
    if unknown:
        raise SystemExit("--days takes %s, got %s" % (",".join(DAYS), unknown))
    w = Window(days, hhmm(a.start), hhmm(a.end), offset(a.utc_offset), [freeze(f) for f in a.freeze])
    hookserver.serve("aegis-change-window", w.decide, a)


if __name__ == "__main__":
    main()
