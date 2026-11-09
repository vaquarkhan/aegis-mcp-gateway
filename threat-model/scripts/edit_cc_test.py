from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-config/src/test/java/io/github/vaquarkhan/aegis/config/CompleteControlConfigTest.java")
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new, 1)

rep('''    @TempDir
    Path dir;
''', '''    @TempDir
    Path dir;

    /** Deployment admission verifier key pair (signs attestations); separate from the review key. */
    private String[] admission;

    private String[] admission() throws Exception {
        if (admission == null) {
            admission = SignedCatalogManifest.generateKeyPair();
            Files.writeString(dir.resolve("admission.pub"), admission[1]);
            Files.writeString(dir.resolve("admission.key"), admission[0]);
        }
        return admission;
    }

    private String attest(String sourceId, Map<String, Object> attestation) throws Exception {
        admission();
        byte[] statement = GatewayFactory.attestationStatement(sourceId, (String) attestation.get("subjectDigest"),
                (String) attestation.get("evidenceDigest"), (String) attestation.get("verifier"),
                (String) attestation.get("validUntil"));
        return SignedCatalogManifest.sign(statement, SignedCatalogManifest.readPrivateKey(dir.resolve("admission.key")));
    }
''')
rep('''        source.put("deploymentAttestation", Map.of(
                "subjectDigest", "sha256:" + "a".repeat(64),
                "evidenceDigest", "sha256:" + "b".repeat(64),
                "verifier", "test.admission/verifier",
                "validUntil", NOW.plusSeconds(86_400).toString()));''', '''        Map<String, Object> attestation = new LinkedHashMap<>(Map.of(
                "subjectDigest", "sha256:" + "a".repeat(64),
                "evidenceDigest", "sha256:" + "b".repeat(64),
                "verifier", "test.admission/verifier",
                "validUntil", NOW.plusSeconds(86_400).toString()));
        try {
            attestation.put("signature", attest("remote", attestation));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        source.put("deploymentAttestation", attestation);''')
rep('''        return context(Map.of("AEGIS_SIGNING_KEY", "k".repeat(48),
                GatewayFactory.REQUIRED_CATALOG_KEY, publicKey.toString()));''', '''        return context(Map.of("AEGIS_SIGNING_KEY", "k".repeat(48),
                GatewayFactory.REQUIRED_CATALOG_KEY, publicKey.toString(),
                GatewayFactory.REQUIRED_ATTESTATION_KEY, dir.resolve("admission.pub").toAbsolutePath().toString()));''')

# new test: forged / missing / re-targeted attestation signatures are refused
rep('''    private ConfigException invalid(Map<String, Object> source) {''', '''    @Test
    void attestationSignatureIsVerifiedWithTheHostAnchoredAdmissionKey() throws Exception {
        try (TestMcpServer server = new TestMcpServer(TestMcpServer.Mode.MODERN)) {
            server.tool("safe", "Safe.", Map.of("type", "object", "properties", Map.of(),
                    "additionalProperties", false), a -> TestMcpServer.textResult("x"));
            // 1. No admission key on the host: complete control refuses to start.
            Map<String, Object> good = source(server, "PURE_DATA", "HANDLE_ONLY");
            String pin = discoverPin(good);
            Path key = sign(good, pin);
            ConfigException noKey = assertThrows(ConfigException.class, () -> GatewayFactory.build(config(good, true, true),
                    context(Map.of("AEGIS_SIGNING_KEY", "k".repeat(48), GatewayFactory.REQUIRED_CATALOG_KEY, key.toString()))));
            assertTrue(noKey.getMessage().contains(GatewayFactory.REQUIRED_ATTESTATION_KEY), noKey.getMessage());

            // 2. A tampered digest (signature no longer matches the statement) is refused, even though the
            //    reviewer signed the block containing it: the admission verifier did not attest this workload.
            Map<String, Object> tampered = source(server, "PURE_DATA", "HANDLE_ONLY");
            @SuppressWarnings("unchecked")
            Map<String, Object> att = new LinkedHashMap<>((Map<String, Object>) tampered.get("deploymentAttestation"));
            att.put("subjectDigest", "sha256:" + "c".repeat(64));
            tampered.put("deploymentAttestation", att);
            Path key2 = sign(tampered, discoverPin(tampered));
            ConfigException forged = assertThrows(ConfigException.class,
                    () -> GatewayFactory.build(config(tampered, true, true), anchored(key2)));
            assertTrue(forged.getMessage().contains("does not verify"), forged.getMessage());

            // 3. A signature by another key (not the host-anchored admission key) is refused.
            Map<String, Object> otherKey = source(server, "PURE_DATA", "HANDLE_ONLY");
            @SuppressWarnings("unchecked")
            Map<String, Object> att3 = new LinkedHashMap<>((Map<String, Object>) otherKey.get("deploymentAttestation"));
            String[] rogue = SignedCatalogManifest.generateKeyPair();
            Path rogueKey = Files.writeString(dir.resolve("rogue.key"), rogue[0]);
            att3.remove("signature");
            att3.put("signature", SignedCatalogManifest.sign(GatewayFactory.attestationStatement("remote",
                    (String) att3.get("subjectDigest"), (String) att3.get("evidenceDigest"), (String) att3.get("verifier"),
                    (String) att3.get("validUntil")), SignedCatalogManifest.readPrivateKey(rogueKey)));
            otherKey.put("deploymentAttestation", att3);
            Path key3 = sign(otherKey, discoverPin(otherKey));
            assertThrows(ConfigException.class, () -> GatewayFactory.build(config(otherKey, true, true), anchored(key3)));

            // 4. Missing signature with the key anchored.
            Map<String, Object> unsigned = source(server, "PURE_DATA", "HANDLE_ONLY");
            @SuppressWarnings("unchecked")
            Map<String, Object> att4 = new LinkedHashMap<>((Map<String, Object>) unsigned.get("deploymentAttestation"));
            att4.remove("signature");
            unsigned.put("deploymentAttestation", att4);
            Path key4 = sign(unsigned, discoverPin(unsigned));
            ConfigException missing = assertThrows(ConfigException.class,
                    () -> GatewayFactory.build(config(unsigned, true, true), anchored(key4)));
            assertTrue(missing.getMessage().contains("signature"), missing.getMessage());
            System.out.println("THREATMODEL|CC-K1|complete-control item: cryptographic attestation verification|"
                    + "GatewayFactory.deploymentAttestation + AEGIS_REQUIRE_ATTESTATION_KEY|FIXED|"
                    + "Ed25519 signature by the host-anchored admission key over source id, workload digest, evidence, verifier, expiry;"
                    + " tampered, rogue-key and unsigned attestations refused");
        }
    }

    private ConfigException invalid(Map<String, Object> source) {''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
