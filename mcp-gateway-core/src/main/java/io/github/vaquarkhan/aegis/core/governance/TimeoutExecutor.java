/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.governance;

import io.github.vaquarkhan.aegis.core.auth.CallerContext;
import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Step 10 execution surface. Runs backend calls on a bounded pool so a slow backend cannot pin the
 * transport thread and a burst cannot grow memory without limit.
 *
 * <p>Pool shape is core 4, maximum 32, queue 128, abort policy. A saturated queue surfaces as a
 * rejected execution, which the chain reports as a backend error rather than queuing forever.
 *
 * @author Viquar Khan
 */
public final class TimeoutExecutor {

    private static final int CORE = 4;
    private static final int MAX = 32;
    private static final int QUEUE = 128;

    private final ThreadPoolExecutor pool;

    public TimeoutExecutor() {
        AtomicInteger n = new AtomicInteger();
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "aegis-backend-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
        this.pool = new ThreadPoolExecutor(
                CORE, MAX, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(QUEUE), tf,
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * Submits {@code work} with the caller identity propagated onto the worker thread.
     * The returned future is the caller's to await and cancel.
     */
    public Future<String> submit(Supplier<String> work, CallerIdentity caller) {
        return pool.submit(() -> {
            if (caller != null) {
                CallerContext.set(caller);
            }
            try {
                return work.get();
            } finally {
                CallerContext.clear();
            }
        });
    }

    public int queueDepth() {
        return pool.getQueue().size();
    }

    public int activeCount() {
        return pool.getActiveCount();
    }

    public void shutdown(long timeoutMillis) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
