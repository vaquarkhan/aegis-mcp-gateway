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
package io.github.vaquarkhan.aegis.mcp.http;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

/**
 * One bounded outbound HTTP exchange. Two implementations: {@link #jdk} (the JDK client, which
 * resolves the host itself at connect time) and {@link PinnedHttp} (connects only to the addresses
 * the egress control approved). Gateway-built clients use {@link PinnedHttp}.
 */
@FunctionalInterface
public interface OutboundHttp {

  /**
   * @throws java.net.http.HttpTimeoutException when the whole exchange exceeds {@code
   *     deadlineMillis}
   * @throws IOException on transport failure, an egress denial, or a body over {@code maxBytes}
   */
  BoundedHttp.Result send(HttpRequest request, int maxBytes, long deadlineMillis)
      throws IOException, InterruptedException;

  /**
   * The JDK client with {@link BoundedHttp}'s total deadline and byte bound (no address pinning).
   */
  static OutboundHttp jdk(HttpClient http) {
    return (request, maxBytes, deadlineMillis) ->
        BoundedHttp.send(http, request, maxBytes, deadlineMillis);
  }
}
