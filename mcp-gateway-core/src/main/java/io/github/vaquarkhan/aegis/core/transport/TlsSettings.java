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
package io.github.vaquarkhan.aegis.core.transport;

import io.github.vaquarkhan.aegis.core.config.GatewayConfig;

/**
 * TLS settings for the HTTP transport.
 *
 * @author Viquar Khan
 */
public final class TlsSettings {

    private final boolean enabled;
    private final String keystorePath;
    private final String keystorePassword;
    private final String keystoreType;

    public TlsSettings(boolean enabled, String keystorePath, String keystorePassword, String keystoreType) {
        this.enabled = enabled;
        this.keystorePath = keystorePath;
        this.keystorePassword = keystorePassword;
        this.keystoreType = (keystoreType == null || keystoreType.isBlank()) ? "PKCS12" : keystoreType;
    }

    public static TlsSettings disabled() {
        return new TlsSettings(false, null, null, "PKCS12");
    }

    public static TlsSettings from(GatewayConfig cfg) {
        return new TlsSettings(
                cfg.httpTlsEnabled(),
                cfg.httpTlsKeystore(),
                cfg.httpTlsKeystorePassword(),
                cfg.httpTlsKeystoreType());
    }

    public boolean enabled() {
        return enabled;
    }

    public String keystorePath() {
        return keystorePath;
    }

    public String keystorePassword() {
        return keystorePassword;
    }

    public String keystoreType() {
        return keystoreType;
    }

    /** Never render the keystore password. */
    @Override
    public String toString() {
        return "TlsSettings[enabled=" + enabled + ", keystore=" + keystorePath
                + ", type=" + keystoreType + "]";
    }
}
