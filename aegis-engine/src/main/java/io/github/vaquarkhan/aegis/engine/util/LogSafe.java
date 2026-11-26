/*
 * Copyright 2026 Viquar Khan and the Aegis MCP Gateway contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.engine.util;

/**
 * Values safe to put in a log line. Strips control characters and newlines (log injection) and
 * truncates length so a hostile request id cannot flood the journal of a broken audit sink.
 */
public final class LogSafe {

  private static final int MAX = 96;

  private LogSafe() {}

  /** Returns a single-line, bounded token; never null. */
  public static String token(Object value) {
    if (value == null) {
      return "-";
    }
    String s = String.valueOf(value);
    StringBuilder out = new StringBuilder(Math.min(s.length(), MAX));
    for (int i = 0; i < s.length() && out.length() < MAX; i++) {
      char c = s.charAt(i);
      if (c >= 0x20 && c < 0x7f && c != '\\') {
        out.append(c);
      } else {
        out.append('_');
      }
    }
    return out.length() == 0 ? "-" : out.toString();
  }
}
