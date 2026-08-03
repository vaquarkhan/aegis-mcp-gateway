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
