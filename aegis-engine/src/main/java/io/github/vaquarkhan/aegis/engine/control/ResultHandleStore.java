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

import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.json.JsonLimits;
import io.github.vaquarkhan.aegis.engine.json.JsonValues;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.spi.StateStore;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handle-bound release for {@code outputPolicy: HANDLE_ONLY}. The model receives only a {@code
 * result:<sha256>} handle; the (already redacted) backend result is kept here for a bounded time so
 * a human reviewer or a trusted downstream consumer can fetch it through the admin API, outside the
 * model's context. Release is take-once: a handle cannot be replayed to read the same data twice.
 * Entries live in the governance {@link StateStore}, so the store's own protection (access control,
 * TLS, encryption at rest) applies; only redacted output is ever stored.
 */
public final class ResultHandleStore {

  private static final String PREFIX = "result-handle:";

  /** A stored result and who produced it. */
  public record Entry(
      String handle, String tool, String requestId, String producer, Map<String, Object> body) {}

  private static final String ENC = "enc:";
  private static final int GCM_NONCE_BYTES = 12;
  private static final int GCM_TAG_BITS = 128;

  private final StateStore store;
  private final Clock clock;
  private final Duration ttl;
  private final int maxBytes;
  private final javax.crypto.SecretKey key;
  private final java.security.SecureRandom random;

  public ResultHandleStore(StateStore store, Clock clock, Duration ttl, int maxBytes) {
    this(store, clock, ttl, maxBytes, null);
  }

  /**
   * @param key optional AES-256 key (P-8). When present every retained body is stored as {@code
   *     enc:base64(nonce||AES-GCM ciphertext)}, so the state store (and anyone who can read it)
   *     never sees the redacted result in clear. The key is held only in memory; losing it only
   *     loses retained handles, which are short-lived by design.
   */
  public ResultHandleStore(
      StateStore store, Clock clock, Duration ttl, int maxBytes, javax.crypto.SecretKey key) {
    if (store == null) {
      throw new IllegalArgumentException("a state store is required");
    }
    if (ttl == null
        || ttl.isNegative()
        || ttl.isZero()
        || ttl.compareTo(Duration.ofHours(24)) > 0) {
      throw new IllegalArgumentException("handle retention must be between 1 second and 24 hours");
    }
    if (key != null && !"AES".equals(key.getAlgorithm())) {
      throw new IllegalArgumentException("handle encryption key must be an AES key");
    }
    this.store = store;
    this.clock = clock == null ? Clock.systemUTC() : clock;
    this.ttl = ttl;
    this.maxBytes = maxBytes <= 0 ? 1 << 20 : maxBytes;
    this.key = key;
    this.random = key == null ? null : new java.security.SecureRandom();
  }

  public boolean encrypted() {
    return key != null;
  }

  private String encode(String json) {
    if (key == null) {
      return json;
    }
    try {
      byte[] nonce = new byte[GCM_NONCE_BYTES];
      random.nextBytes(nonce);
      javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          javax.crypto.Cipher.ENCRYPT_MODE,
          key,
          new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, nonce));
      byte[] ct = c.doFinal(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] out = new byte[nonce.length + ct.length];
      System.arraycopy(nonce, 0, out, 0, nonce.length);
      System.arraycopy(ct, 0, out, nonce.length, ct.length);
      return ENC + java.util.Base64.getEncoder().encodeToString(out);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException("could not encrypt a retained result handle", e);
    }
  }

  private String decode(String stored) {
    if (stored == null || !stored.startsWith(ENC)) {
      return stored; // written without a key, or by an older build
    }
    if (key == null) {
      throw new IllegalStateException(
          "a retained handle is encrypted but no handle encryption key is configured");
    }
    try {
      byte[] blob = java.util.Base64.getDecoder().decode(stored.substring(ENC.length()));
      if (blob.length <= GCM_NONCE_BYTES) {
        throw new IllegalStateException("truncated encrypted handle");
      }
      javax.crypto.Cipher c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
      c.init(
          javax.crypto.Cipher.DECRYPT_MODE,
          key,
          new javax.crypto.spec.GCMParameterSpec(GCM_TAG_BITS, blob, 0, GCM_NONCE_BYTES));
      byte[] pt = c.doFinal(blob, GCM_NONCE_BYTES, blob.length - GCM_NONCE_BYTES);
      return new String(pt, java.nio.charset.StandardCharsets.UTF_8);
    } catch (java.security.GeneralSecurityException e) {
      throw new IllegalStateException(
          "could not decrypt a retained result handle (wrong key or tampered store)", e);
    }
  }

  public Duration ttl() {
    return ttl;
  }

  /**
   * Stores the redacted result; returns false when it is too large to retain (the handle stays
   * opaque).
   */
  public boolean put(
      String handle,
      String tool,
      String requestId,
      CallerIdentity producer,
      Map<String, Object> redactedBody) {
    Map<String, Object> rec = new LinkedHashMap<>();
    rec.put("tool", tool);
    rec.put("requestId", requestId);
    rec.put("producer", producer == null ? null : producer.principalKey());
    rec.put("body", redactedBody);
    String json = Json.write(rec);
    if (json.length() > maxBytes) {
      return false;
    }
    try {
      // Never overwrite: a retained entry keeps its producer for the separation-of-duties check.
      return store.putIfAbsent(PREFIX + handle, encode(json), clock.instant().plus(ttl));
    } catch (IllegalStateException full) {
      return false; // store full: the handle stays opaque, the call is not failed after it ran
    }
  }

  /** Who produced a retained handle (for separation-of-duties checks), without consuming it. */
  public Optional<String> producer(String handle) {
    if (handle == null || !handle.matches("result:[0-9a-f]{64}")) {
      return Optional.empty();
    }
    return store
        .get(PREFIX + handle)
        .map(
            stored ->
                JsonValues.string(
                    Json.parseObject(
                        decode(stored).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        JsonLimits.DEFAULT.withMaxBytes(maxBytes + 1024)),
                    "producer"));
  }

  /** Take-once release. Empty when unknown, expired or already released. */
  public Optional<Entry> take(String handle) {
    if (handle == null || !handle.matches("result:[0-9a-f]{64}")) {
      return Optional.empty();
    }
    return store
        .take(PREFIX + handle)
        .map(
            stored -> {
              Map<String, Object> rec =
                  Json.parseObject(
                      decode(stored).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                      JsonLimits.DEFAULT.withMaxBytes(maxBytes + 1024));
              return new Entry(
                  handle,
                  JsonValues.string(rec, "tool"),
                  JsonValues.string(rec, "requestId"),
                  JsonValues.string(rec, "producer"),
                  JsonValues.object(rec, "body"));
            });
  }
}
