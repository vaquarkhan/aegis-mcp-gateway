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
/*
 * Threat-model reproduction suite (own HTTP server). Verdict lines: THREATMODEL|id|refs|class|verdict|detail
 */
package io.github.vaquarkhan.aegis.gateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ThreatModelCveServerTest {

  static void verdict(String id, String refs, String cls, String verdict, String detail) {
    System.out.println("THREATMODEL|" + id + "|" + refs + "|" + cls + "|" + verdict + "|" + detail);
  }

  @Test
  void open02_saturatedWorkerPool_refusesInsteadOfBlockingTheAcceptThread() throws Exception {
    AegisServer.applyServerTimeLimits();
    assertNotNull(
        System.getProperty("sun.net.httpserver.maxReqHeaders"),
        "header-count bound set before auth parses headers");
    assertNotNull(
        System.getProperty("sun.net.httpserver.maxReqHeaderSize"), "header-size bound set");
    assertNotNull(System.getProperty("jdk.httpserver.maxConnections"), "connection bound set");
    assertEquals("30", System.getProperty("sun.net.httpserver.maxReqTime"));

    int threads = 2;
    ThreadPoolExecutor pool = AegisServer.boundedPool(threads);
    CountDownLatch release = new CountDownLatch(1);
    Runnable slowClient =
        () -> {
          try {
            release.await(30, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    try {
      // Fill every worker and every queue slot with half-sent requests.
      for (int i = 0; i < threads + threads * 8; i++) {
        pool.execute(slowClient);
      }
      // BEFORE the fix (CallerRunsPolicy) the next execute() ran the slow client on the CALLING
      // thread,
      // which in production is the JDK accept/dispatch thread: nobody else gets accepted.
      long t0 = System.nanoTime();
      assertThrows(RejectedExecutionException.class, () -> pool.execute(slowClient));
      long ms = (System.nanoTime() - t0) / 1_000_000;
      assertTrue(ms < 1_000, "rejection must be immediate, took " + ms + " ms");
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
    verdict(
        "OPEN-02",
        "review finding (pre-authentication worker exhaustion on the JDK HttpServer)",
        "slow-header / connection flood before authentication",
        "FIXED",
        "maxReqHeaders=100, maxReqHeaderSize=16 KiB, maxConnections=1024 set if absent; saturated pool rejects (JDK closes the connection) instead of CallerRunsPolicy");
  }
}
