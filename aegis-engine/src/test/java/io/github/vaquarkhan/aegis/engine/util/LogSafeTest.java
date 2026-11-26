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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogSafeTest {

  @Test
  void nullAndEmptyBecomeDash() {
    assertEquals("-", LogSafe.token(null));
    assertEquals("-", LogSafe.token(""));
  }

  @Test
  void stripsNewlinesAndControlCharacters() {
    String t = LogSafe.token("ok\ninject\r\t\u0000x");
    assertFalse(t.contains("\n"));
    assertFalse(t.contains("\r"));
    assertTrue(t.startsWith("ok_inject"));
  }

  @Test
  void truncatesLongValues() {
    assertEquals(96, LogSafe.token("a".repeat(200)).length());
  }
}
