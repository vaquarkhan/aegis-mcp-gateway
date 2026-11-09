from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-mcp/src/main/java/io/github/vaquarkhan/aegis/mcp/admin/AdminApi.java")
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new, 1)

rep('''    private final java.time.Duration revocationRetention;
''', '''    private final java.time.Duration revocationRetention;
    private final Set<String> resultReleaseGroups;
''')
rep('''        this.revocationRetention = java.time.Duration.ofHours(24);
    }
''', '''        this.revocationRetention = java.time.Duration.ofHours(24);
        this.resultReleaseGroups = Set.of();
    }
''')
rep('''    private AdminApi(AdminApi base, Set<String> revokerGroups, TokenRevocations revocations, java.time.Duration retention) {''',
    '''    private AdminApi(AdminApi base, Set<String> revokerGroups, TokenRevocations revocations, java.time.Duration retention) {
        this(base, revokerGroups, revocations, retention, base.resultReleaseGroups);
    }

    private AdminApi(AdminApi base, Set<String> revokerGroups, TokenRevocations revocations, java.time.Duration retention,
                     Set<String> resultReleaseGroups) {''')
rep('''        this.revocationRetention = retention == null || retention.isNegative() || retention.isZero()
                ? java.time.Duration.ofHours(24) : retention;
    }''', '''        this.revocationRetention = retention == null || retention.isNegative() || retention.isZero()
                ? java.time.Duration.ofHours(24) : retention;
        this.resultReleaseGroups = resultReleaseGroups == null ? Set.of() : Set.copyOf(resultReleaseGroups);
    }

    /**
     * Same API plus handle-bound result release for {@code outputPolicy: HANDLE_ONLY}:
     * <pre>
     *   POST /results/{handle}/release   take-once release of the redacted result (result release groups)
     * </pre>
     * The identity that produced the handle can never release it (the model's caller must not be able
     * to read what the policy withheld from it), the releaser must be non-delegated, and the intent is
     * journaled before the data leaves the store.
     */
    public AdminApi withResultRelease(Set<String> groups) {
        return new AdminApi(this, revokerGroups, revocations, revocationRetention, groups);
    }''')
rep('''            if (parts.length >= 1 && "catalog".equals(parts[0])) {
                if (!caller.inAnyGroup(approverGroups)) {''', '''            if (parts.length == 3 && "results".equals(parts[0]) && "release".equals(parts[2]) && "POST".equals(req.method())
                    && engine.resultHandles() != null) {
                return releaseResult(caller, parts[1]);
            }
            if (parts.length >= 1 && "catalog".equals(parts[0])) {
                if (!caller.inAnyGroup(approverGroups)) {''')
rep('''    /**
     * Token revocation: only revoker groups,''', '''    private McpHttpResponse releaseResult(CallerIdentity caller, String handle) {
        if (resultReleaseGroups.isEmpty() || !caller.inAnyGroup(resultReleaseGroups)) {
            return McpHttpResponse.json(403, Map.of("error", "caller is not in a result release group"));
        }
        if (!caller.actorChain().isEmpty()
                || !ApprovalService.identityKey(caller.actor()).equals(ApprovalService.identityKey(caller.effectiveSubject()))) {
            return McpHttpResponse.json(403, Map.of("error", "result release must be made by a non-delegated identity"));
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("handle", handle);
        try {
            engine.journalAdministrative("RESULT_RELEASE_INTENT", caller, details);
        } catch (Exception e) {
            return McpHttpResponse.json(503, Map.of("error", "the audit journal could not record this release, so it was not made"));
        }
        java.util.Optional<io.github.vaquarkhan.aegis.engine.control.ResultHandleStore.Entry> taken =
                engine.resultHandles().take(handle);
        if (taken.isEmpty()) {
            return McpHttpResponse.json(404, Map.of("error", "unknown, expired or already released handle"));
        }
        io.github.vaquarkhan.aegis.engine.control.ResultHandleStore.Entry e = taken.get();
        if (caller.principalKey().equals(e.producer())) {
            // Put it back is not possible without the original TTL; the release is refused and the entry
            // is consumed, which fails safe (the data is not disclosed to its producer).
            details.put("refused", "the producer of a handle cannot release it");
            try {
                engine.journalAdministrative("RESULT_RELEASE_REFUSED", caller, details);
            } catch (Exception ignored) {
                // refusal stands
            }
            return McpHttpResponse.json(403, Map.of("error", "the identity that produced this handle cannot release it"));
        }
        details.put("tool", e.tool());
        details.put("requestId", e.requestId());
        try {
            engine.journalAdministrative("RESULT_RELEASE_RESULT", caller, details);
        } catch (Exception ignored) {
            // intent already journaled
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("handle", handle);
        out.put("tool", e.tool());
        out.put("requestId", e.requestId());
        out.put("result", e.body());
        return McpHttpResponse.json(200, out);
    }

    /**
     * Token revocation: only revoker groups,''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
