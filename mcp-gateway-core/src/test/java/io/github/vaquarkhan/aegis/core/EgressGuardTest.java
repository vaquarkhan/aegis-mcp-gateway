package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.governance.EgressGuard;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * @author Viquar Khan
 */
class EgressGuardTest {

    @Test
    void deniesCloudMetadataEvenWhenAllowListed() {
        EgressGuard guard = new EgressGuard(Set.of("169.254.169.254", "*"));
        assertFalse(guard.isAllowed("169.254.169.254"));
        assertFalse(guard.isAllowed("http://169.254.169.254/latest/meta-data/"));
        assertNotNull(guard.denyReason("http://169.254.169.254/latest/meta-data/"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "169.254.169.254",
            "169.254.0.1",
            "metadata.google.internal",
            "metadata",
            "100.100.100.200",
            "0.0.0.0",
            "255.255.255.255",
            "fe80::1",
            "fd00:ec2::254",
            "239.1.1.1",
            "2852039166",
            "0xa9fea9fe",
            "0251.0376.0251.0376"
    })
    void unconditionalDenyList(String host) {
        assertTrue(EgressGuard.isDeniedHost(host), host);
    }

    @Test
    void deniesAlternateEncodingsEvenUnderWildcard() {
        EgressGuard guard = new EgressGuard(Set.of("*"));
        assertFalse(guard.isAllowed("http://2852039166/"));
        assertFalse(guard.isAllowed("http://0xA9FEA9FE/"));
        assertFalse(guard.isAllowed("http://0251.0376.0251.0376/"));
    }

    @Test
    void connectPinRejectsMetadataLiteral() {
        assertThrows(IllegalArgumentException.class,
                () -> io.github.vaquarkhan.aegis.core.governance.EgressConnect.pin("http://169.254.169.254/"));
        assertThrows(IllegalArgumentException.class,
                () -> io.github.vaquarkhan.aegis.core.governance.EgressConnect.pin("http://2852039166/"));
    }

    @Test
    void allowsListedHosts() {
        EgressGuard guard = new EgressGuard(Set.of("flink.internal", "kafka.internal"));
        assertTrue(guard.isAllowed("flink.internal"));
        assertTrue(guard.isAllowed("http://flink.internal:8081/jobs/overview"));
        assertTrue(guard.isAllowed("kafka.internal:9092"));
        assertNull(guard.denyReason("flink.internal"));
    }

    @Test
    void deniesUnlistedHosts() {
        EgressGuard guard = new EgressGuard(Set.of("flink.internal"));
        assertFalse(guard.isAllowed("evil.example.com"));
        assertTrue(guard.denyReason("evil.example.com").contains("not in egress allow list"));
    }

    @Test
    void emptyAllowListDeniesEverything() {
        EgressGuard guard = new EgressGuard(Set.of());
        assertFalse(guard.isAllowed("flink.internal"));
        assertTrue(guard.denyReason("flink.internal").contains("no egress allow list"));
    }

    @Test
    void wildcardAllowsOrdinaryHostsButNotMetadata() {
        EgressGuard guard = new EgressGuard(Set.of("*"));
        assertTrue(guard.isAllowed("anything.example.com"));
        assertFalse(guard.isAllowed("169.254.169.254"));
    }

    @Test
    void extractsHostFromVariousForms() {
        assertEquals("example.com", EgressGuard.extractHost("example.com"));
        assertEquals("example.com", EgressGuard.extractHost("example.com:8080"));
        assertEquals("example.com", EgressGuard.extractHost("https://example.com/path?q=1"));
        assertEquals("example.com", EgressGuard.extractHost("HTTPS://EXAMPLE.COM/"));
        assertEquals("::1", EgressGuard.extractHost("[::1]:8080"));
        assertNull(EgressGuard.extractHost(null));
        assertNull(EgressGuard.extractHost("   "));
    }

    @Test
    void stripsUserInfoBeforeMatching() {
        EgressGuard guard = new EgressGuard(Set.of("flink.internal"));
        assertFalse(guard.isAllowed("flink.internal@169.254.169.254"));
    }

    @Test
    void normalisesAllowListCase() {
        EgressGuard guard = new EgressGuard(Set.of("Flink.Internal"));
        assertTrue(guard.isAllowed("flink.internal"));
        assertEquals(Set.of("flink.internal"), guard.allowHosts());
    }
}
