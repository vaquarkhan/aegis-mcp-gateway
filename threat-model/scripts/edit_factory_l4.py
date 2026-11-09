from pathlib import Path
_REPO = Path(__file__).resolve().parents[2]
_TM = Path(__file__).resolve().parents[1]
p = str(_REPO / r"aegis-config/src/main/java/io/github/vaquarkhan/aegis/config/GatewayFactory.java")
s = open(p, encoding='utf-8').read()

def rep(old, new):
    global s
    assert old in s, old[:100]
    s = s.replace(old, new, 1)

# 1. complete-control node and plugin artifacts first (secret providers are plugins too).
rep('''        final BuildContext ctx = secrets(root.child("secrets"), raw, callerContext).withFreshEgressBinding();

        ConfigNode gw = root.child("gateway");
        String gatewayName = gw.string("name", "aegis");

        ConfigNode completeControlNode = root.child("completeControl");
        boolean completeControl = completeControlNode.bool("enabled", false);
''', '''        // Complete control is read first: every plugin lookup below (secret, hook, state, audit sink)
        // is limited to provider JARs hash-verified from completeControl.artifacts (role plugin).
        ConfigNode completeControlNode = root.child("completeControl");
        boolean completeControl = completeControlNode.bool("enabled", false);
        List<ArtifactAdmission.Verified> pluginArtifacts = ArtifactAdmission.verify(completeControlNode,
                callerContext.baseDir());
        final BuildContext ctx = secrets(root.child("secrets"), raw, callerContext, pluginArtifacts, completeControl)
                .withFreshEgressBinding();

        ConfigNode gw = root.child("gateway");
        String gatewayName = gw.string("name", "aegis");
''')

# 2. secrets(): plugin admission
rep('''    private static BuildContext secrets(ConfigNode s, Map<String, Object> raw, BuildContext ctx) throws Exception {''',
    '''    private static BuildContext secrets(ConfigNode s, Map<String, Object> raw, BuildContext ctx,
                                        List<ArtifactAdmission.Verified> pluginArtifacts, boolean completeControl) throws Exception {''')
rep('''                SecretProviderFactory factory = provider(SecretProviderFactory.class, source, SecretProviderFactory::type)''',
    '''                SecretProviderFactory factory = provider(SecretProviderFactory.class, source, SecretProviderFactory::type,
                        pluginArtifacts, completeControl)''')

# 3. hooks
rep('''                GovernanceHookFactory f = provider(GovernanceHookFactory.class, type, GovernanceHookFactory::type)''',
    '''                GovernanceHookFactory f = provider(GovernanceHookFactory.class, type, GovernanceHookFactory::type,
                        pluginArtifacts, completeControl)''')

# 4. state
rep('''                StateStoreFactory factory = provider(StateStoreFactory.class, stateType, StateStoreFactory::type)''',
    '''                StateStoreFactory factory = provider(StateStoreFactory.class, stateType, StateStoreFactory::type,
                        pluginArtifacts, completeControl)''')

# 5. audit sinks
rep('''        boolean auditArgs = audit.bool("includeArguments", false);
''', '''        boolean auditArgs = audit.bool("includeArguments", false);
        // Additional destinations (SIEM, bus, cloud log) through the AuditSinkFactory SPI. Every sink
        // receives every record after the journal, and any failure fails the call closed.
        List<AuditSink> extraSinks = new ArrayList<>();
        for (ConfigNode sinkNode : audit.children("sinks")) {
            String sinkType = sinkNode.requiredString("type");
            io.github.vaquarkhan.aegis.config.spi.AuditSinkFactory f = provider(
                    io.github.vaquarkhan.aegis.config.spi.AuditSinkFactory.class, sinkType,
                    io.github.vaquarkhan.aegis.config.spi.AuditSinkFactory::type, pluginArtifacts, completeControl)
                    .orElseThrow(() -> new ConfigException(sinkNode.path() + ".type unknown or unadmitted audit sink type '"
                            + sinkType + "' (add an AuditSinkFactory provider jar)"));
            AuditSink created = f.create(sinkNode.child("settings"), ctx);
            if (created == null) {
                throw new ConfigException(sinkNode.path() + ".type provider '" + sinkType + "' produced no sink");
            }
            extraSinks.add(created);
        }
''')
rep('''        AuditSink sink = auditJournal;
        if (ctx.extraAuditSink() != null) {
            sink = tee(sink, ctx.extraAuditSink());
        }''', '''        AuditSink sink = auditJournal;
        for (AuditSink extra : extraSinks) {
            sink = tee(sink, extra);
        }
        if (ctx.extraAuditSink() != null) {
            sink = tee(sink, ctx.extraAuditSink());
        }''')

# 6. screening.results default
rep('''            resultsMode = ControlMode.parse(scr.string("results", "AUDIT"));
        } catch (IllegalArgumentException e) {
            throw scr.error("results", "must be ENFORCE, AUDIT or OFF");
        }''', '''            // Complete control withholds an injection-bearing result by default and never turns screening off.
            resultsMode = ControlMode.parse(scr.string("results", completeControl ? "ENFORCE" : "AUDIT"));
        } catch (IllegalArgumentException e) {
            throw scr.error("results", "must be ENFORCE, AUDIT or OFF");
        }
        if (completeControl && resultsMode == ControlMode.OFF) {
            throw scr.error("results", "must not be OFF when completeControl.enabled is true");
        }''')

# 7. PII redaction
rep('''        redact.addAll(out.strings("redact"));''', '''        // Opt-in personal-data patterns (email, phone, payment card, US SSN, IBAN); on by default in complete control.
        if (out.bool("redactPii", completeControl)) {
            redact.addAll(OutputGuard.PII_REDACTIONS);
        }
        redact.addAll(out.strings("redact"));''')

open(p, 'w', encoding='utf-8', newline='').write(s)
print('ok')
