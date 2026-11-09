from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
import re
root = str(_REPO)
gf = root + r'\aegis-config\src\main\java\io\github\vaquarkhan\aegis\config\GatewayFactory.java'
sm = root + r'\aegis-engine\src\main\java\io\github\vaquarkhan\aegis\engine\catalog\SignedCatalogManifest.java'

s = open(sm, encoding='utf-8').read()
old = '''    static boolean verify(byte[] body, byte[] sig, PublicKey key) throws IOException {'''
new = '''    /**
     * Verifies a base64 Ed25519 signature over {@code body} (used for catalog manifests and for
     * deployment attestations). A malformed signature is simply invalid.
     */
    public static boolean verifySignature(byte[] body, String base64Signature, PublicKey key) throws IOException {
        byte[] sig;
        try {
            sig = Base64.getDecoder().decode(base64Signature == null ? "" : base64Signature.strip());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return verify(body, sig, key);
    }

    static boolean verify(byte[] body, byte[] sig, PublicKey key) throws IOException {'''
assert old in s
s = s.replace(old, new, 1)
open(sm, 'w', encoding='utf-8', newline='').write(s)

s = open(gf, encoding='utf-8').read()
def rep(old, new, count=1):
    global s
    assert old in s, old[:120]
    s = s.replace(old, new, count)

# host anchored attestation key, read once from the caller (process) context
rep('''        Path requiredReviewKey = requiredCatalogKey(callerContext);''',
    '''        Path requiredReviewKey = requiredCatalogKey(callerContext);
        // Deployment admission key: the admission verifier signs each remote source's attestation. Host
        // anchored like the review key (environment or JVM property, never the configuration file).
        java.security.PublicKey attestationKey = requiredAttestationKey(callerContext);
        if (completeControl && attestationKey == null) {
            throw new ConfigException("config.completeControl.enabled requires host-side " + REQUIRED_ATTESTATION_KEY
                    + " (the deployment admission verifier's public key), so a remote source's attestation is verified,"
                    + " not only shape-checked");
        }''')
rep('''            SourceBuild sb = source(s, ctx, completeControl);''',
    '''            SourceBuild sb = source(s, ctx, completeControl, attestationKey);''')
rep('''    private static SourceBuild source(ConfigNode s, BuildContext ctx, boolean completeControl) throws Exception {''',
    '''    private static SourceBuild source(ConfigNode s, BuildContext ctx, boolean completeControl,
                                      java.security.PublicKey attestationKey) throws Exception {''')
rep('''        sb.deploymentSubjectDigest = deploymentAttestation(s, ctx, completeControl);''',
    '''        sb.deploymentSubjectDigest = deploymentAttestation(s, ctx, completeControl, attestationKey);''')
rep('''     * Reviewed external attestation bound by the host-anchored signed source block. This verifies the
     * receipt shape and lifetime; the deployment admission system remains responsible for issuing it
     * only after checking the actual image/workload digest.
     */
    private static String deploymentAttestation(ConfigNode source, BuildContext ctx, boolean completeControl) {''',
    '''     * Reviewed external attestation bound by the host-anchored signed source block. Shape and lifetime
     * are always checked. When the host names {@link #REQUIRED_ATTESTATION_KEY} (always in complete
     * control) the attestation must also carry {@code signature}: an Ed25519 signature by the deployment
     * admission verifier over {@link #attestationStatement}, which binds the source id, workload
     * digest, evidence digest, verifier identity and expiry. The admission system remains responsible
     * for signing only after checking the actual image/workload digest.
     */
    private static String deploymentAttestation(ConfigNode source, BuildContext ctx, boolean completeControl,
                                                java.security.PublicKey attestationKey) {''')
rep('''        if (completeControl && expires.isAfter(ctx.clock().instant().plus(Duration.ofDays(30)))) {
            throw a.error("validUntil", "must be no more than 30 days ahead in complete control");
        }
        return subject;
    }''', '''        if (completeControl && expires.isAfter(ctx.clock().instant().plus(Duration.ofDays(30)))) {
            throw a.error("validUntil", "must be no more than 30 days ahead in complete control");
        }
        String signature = a.string("signature", null);
        if (attestationKey != null) {
            if (signature == null || signature.isBlank()) {
                throw a.error("signature", "is required: " + REQUIRED_ATTESTATION_KEY + " names the admission verifier key");
            }
            byte[] statement = attestationStatement(source.requiredString("id"), subject, evidence, verifier, expiresText);
            try {
                if (!SignedCatalogManifest.verifySignature(statement, signature, attestationKey)) {
                    throw a.error("signature", "does not verify with the admission key named by " + REQUIRED_ATTESTATION_KEY);
                }
            } catch (java.io.IOException e) {
                throw a.error("signature", "could not be verified: " + e.getMessage());
            }
        }
        return subject;
    }

    /** The exact bytes a deployment admission verifier signs for one source. */
    public static byte[] attestationStatement(String sourceId, String subjectDigest, String evidenceDigest, String verifier,
                                              String validUntil) {
        return ("aegis-deployment-attestation-v1\\n" + sourceId + "\\n" + subjectDigest + "\\n" + evidenceDigest + "\\n"
                + verifier + "\\n" + validUntil + "\\n").getBytes(StandardCharsets.UTF_8);
    }''')
rep('''    /** JVM system property alternative to {@link #REQUIRED_CATALOG_KEY}, for hosts such as Knox. */''',
    '''    /**
     * Host-level setting naming the deployment admission verifier's Ed25519 public key (X.509 PEM,
     * absolute path). When set (required in complete control) every deploymentAttestation must carry a
     * signature by that key. JVM property alternative: {@code aegis.requireAttestationKey}.
     */
    public static final String REQUIRED_ATTESTATION_KEY = "AEGIS_REQUIRE_ATTESTATION_KEY";

    private static java.security.PublicKey requiredAttestationKey(BuildContext host) {
        String v = host.env().apply(REQUIRED_ATTESTATION_KEY);
        if (v == null || v.isBlank()) {
            v = System.getProperty("aegis.requireAttestationKey");
        }
        if (v == null || v.isBlank()) {
            return null;
        }
        Path p = Path.of(v.strip());
        if (!p.isAbsolute()) {
            throw new ConfigException(REQUIRED_ATTESTATION_KEY + " must be an absolute path to the admission public key");
        }
        try {
            return SignedCatalogManifest.readPublicKey(p);
        } catch (java.io.IOException e) {
            throw new ConfigException(REQUIRED_ATTESTATION_KEY + ": cannot read the admission public key: " + e.getMessage(), e);
        }
    }

    /** JVM system property alternative to {@link #REQUIRED_CATALOG_KEY}, for hosts such as Knox. */''')
open(gf, 'w', encoding='utf-8', newline='').write(s)
print('ok')
