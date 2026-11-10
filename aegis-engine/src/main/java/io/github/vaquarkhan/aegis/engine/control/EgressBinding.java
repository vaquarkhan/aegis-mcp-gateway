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
package io.github.vaquarkhan.aegis.engine.control;

import java.net.URI;

/**
 * Late binding of the gateway's {@link EgressGuard} for outbound transports that are constructed
 * before the guard exists (sources are built first because their hosts join the allow list). Until
 * {@link #bind} is called every pin is denied, so a transport can never connect unchecked. Bound
 * once: a second bind is refused so a built gateway cannot be pointed at a weaker guard.
 */
public final class EgressBinding {

  private volatile EgressGuard guard;
  private volatile Object proxy;

  /**
   * Binds the guard and an optional transport-specific forward proxy descriptor; may be called
   * once.
   */
  public synchronized void bind(EgressGuard g, Object forwardProxy) {
    bind(g);
    proxy = forwardProxy;
  }

  /** The forward proxy descriptor bound with the guard, or null. */
  public Object proxy() {
    return proxy;
  }

  /** Binds the guard; may be called once. */
  public synchronized void bind(EgressGuard g) {
    if (g == null) {
      throw new IllegalArgumentException("egress guard is required");
    }
    if (guard != null && guard != g) {
      throw new IllegalStateException("the egress guard is already bound");
    }
    guard = g;
  }

  public boolean bound() {
    return guard != null;
  }

  /** Resolve-and-check the target with the bound guard; denied while unbound. */
  public EgressGuard.Pin pin(URI target) {
    EgressGuard g = guard;
    if (g == null) {
      return new EgressGuard.Pin(
          "egress guard is not initialised; outbound connection refused", null, null);
    }
    return g.pin(target);
  }
}
