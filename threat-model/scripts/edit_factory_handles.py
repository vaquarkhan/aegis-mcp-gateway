from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-config/src/main/java/io/github/vaquarkhan/aegis/config/GatewayFactory.java")
s = open(p, encoding='utf-8').read()
def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new, 1)

rep('''        List<ArtifactAdmission.Verified> pluginArtifacts = ArtifactAdmission.verify(completeControlNode,
                callerContext.baseDir());''', '''        List<ArtifactAdmission.Verified> pluginArtifacts = ArtifactAdmission.verify(completeControlNode,
                callerContext.baseDir());
        // HANDLE_ONLY results retained (redacted, take-once) for release outside the model; 0 = not retained.
        long handleRetention = completeControlNode.integer("handleRetentionSeconds", 0);
        if (handleRetention < 0 || handleRetention > 86_400) {
            throw completeControlNode.error("handleRetentionSeconds", "must be between 0 (off) and 86400");
        }''')
rep('''        Set<String> revokerGroups = access.stringSet("revokerGroups");''', '''        Set<String> revokerGroups = access.stringSet("revokerGroups");
        // Who may release a retained HANDLE_ONLY result (POST adminPath/results/{handle}/release).
        Set<String> resultReleaseGroups = access.stringSet("resultReleaseGroups");''')
rep('''        GovernanceEngine engine = new GovernanceEngine(settings, catalog, pdp, approvals, vrp, state, sink, creds,
                breakers, ctx.clock(), checkArgumentUrls);''', '''        GovernanceEngine engine = new GovernanceEngine(settings, catalog, pdp, approvals, vrp, state, sink, creds,
                breakers, ctx.clock(), checkArgumentUrls);
        if (handleRetention > 0) {
            engine.enableResultHandles(new io.github.vaquarkhan.aegis.engine.control.ResultHandleStore(state, ctx.clock(),
                    Duration.ofSeconds(handleRetention), outputGuard.maxBytes()));
        }''')
rep('''                .withRevocations(revokerGroups, revocations, Duration.ofSeconds(revocationRetention));''',
    '''                .withRevocations(revokerGroups, revocations, Duration.ofSeconds(revocationRetention))
                .withResultRelease(resultReleaseGroups);''')
open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
