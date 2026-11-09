# Change log: folders and files touched, 2026-11-05 to 2026-11-07

This lists everything changed in the last three days of work on Aegis 2.0. Two trees are
involved: the gateway repository (base commit `2f7b944`, all changes uncommitted) and the
`threat-model/` working folder that holds the analysis, evidence and the demo.

Nothing here has been committed or pushed.

## 1. Gateway repository: `aegis-mcp-gateway-20261104-2.0-review-ready`

95 tracked files changed versus `HEAD`, plus 4 new website pages still untracked. Grouped by area.

### 1a. Security hardening (passes 1 to 7)

Source and test files across eight modules. New files are marked (new).

- `aegis-config`: `BuildContext`, `ConfigLoader`, `Gateway`, `GatewayFactory`,
  `credentials/TokenExchangeCredentials`, `hooks/RemoteHook`, `hooks/SqlReadOnlyHook`,
  `http/HttpToolExecutor`, `openapi/OpenApiDraft`, `policy/OpaPolicy`,
  `secrets/VaultKvSecretProvider`; (new) `ArtifactAdmission`, `spi/AuditSinkFactory`;
  tests (new) `ArtifactAdmissionTest`, `CompleteControlConfigTest`, `DnsRebindingConfigTest`,
  `ThreatModelClosureConfigTest`, `ThreatModelGapConfigTest`, `hooks/ThreatModelCveSqlTest`;
  (new) service resource `META-INF/services/...AuditSinkFactory`.
- `aegis-engine`: `GovernanceEngine`, `approval/ApprovalService`,
  `audit/AuditCheckpointPolicy`, `audit/AuditCheckpointVerifier`, `audit/HashChainFileAuditSink`,
  `catalog/CatalogIntegrity`, `catalog/GovernedTool`, `catalog/SignedCatalogManifest`,
  `catalog/ToolCatalog`, `content/ContentGovernor`, `control/EgressGuard`,
  `control/HookBinding`, `control/HookRunner`, `control/InjectionScreen`,
  `control/OutputGuard`, `control/RateLimiter`, `state/InMemoryStateStore`;
  (new) `catalog/ExecutionContract`, `catalog/OutputPolicy`, `control/EgressBinding`,
  `control/ResultHandleStore`, `control/TokenRevocations`;
  tests (new) `CompleteControlEngineTest`, `ThreatModelClosureEngineTest`, `ThreatModelCveEngineTest`.
- `aegis-gateway`: `AegisServer`, `PolicyTestCommand`; tests (new) `ThreatModelCveServerTest`,
  `ThreatModelGapServerTest`.
- `aegis-knox3-extension`: `KnoxIdentityBridge`, `rs/AegisMcpResource`; test (new) `KnoxRevocationTest`.
- `aegis-mcp-security`: `identity/JwtVerifier`, `perimeter/OwnServerPerimeter`,
  `perimeter/OwnServerSecuritySettings`; (new) `perimeter/ClientAddressLimiter`;
  tests (new) `ThreatModelCveAuthTest`, `ThreatModelGapJwtTest`, `ClientAddressLimiterTest`,
  `ThreatModelCvePerimeterTest`.
- `aegis-mcp`: `admin/AdminApi`, `client/ClientSupport`, `client/HttpMcpClient`,
  `client/StdioMcpClient`, `server/SessionRegistry`, `testing/TestStdioMcpServer`;
  (new) `client/ProcessLauncher`, `http/OutboundHttp`, `http/PinnedHttp`;
  tests (new) `admin/AdminApiGapTest`, `client/CompleteControlStdioTest`,
  `client/ThreatModelCveUpstreamNameTest`, `http/PinnedHttpTest`, `server/ThreatModelCveTransportTest`.
- `aegis-observability-otlp`: `OtlpSpanExporter`.
- `aegis-source-a2a`: `A2aToolSource`, `A2aToolSourceFactory`, `A2aToolSourceTest`.
- Build and CI: `pom.xml`, (new) `spotbugs-exclude.xml`, `.github/workflows/aegis-2.0.yml`,
  (new) `.github/workflows/codeql.yml`.
- Helm chart: (new) `charts/aegis/ci/complete-control-values.yaml`,
  `charts/aegis/ci/test-values.yaml`, `charts/aegis/templates/_helpers.tpl`,
  `charts/aegis/templates/statefulset.yaml`, `charts/aegis/values.yaml`.

### 1b. Website and documentation (this session)

- (new) `docs/assets/complete-control-plane.svg`
- (new) `docs/guide/threat-modeling.html`
- (new) `docs/guide/mcp-cve-containment.html`
- (new) `docs/guide/deploy.html`
- (new) `docs/guide/responsible-ai.html`
- (new) `docs/guide/tutorials.html`
- modified navigation and cards: `docs/guide/security.html`, `docs/guide/handbook.html`,
  `docs/guide/modules.html`, `docs/guide/developer.html`, `docs/guide/hello-world.html`,
  `docs/index.html`

## 2. Working folder: `threat-model/`

Created and updated 2026-11-06 to 2026-11-07. Holds the analysis, evidence and the local demo.

- Analysis documents: `THREAT-MODEL.md`, `DETAILED-FINDINGS-AND-FIXES.md`,
  `OWASP-TOP10-COVERAGE.md`, `AEGIS-VS-CONTEXTFORGE.md`, `AI-SCANNER-READINESS.md`,
  `COMPLETE-CONTROL-EXPLORATION.md`, `DECISIONS.md`, `README.md`, `complete-control-plane.svg`,
  and this change log.
- `cve-demo/README.md` (new): the three-CVE before-and-after containment write-up.
- `cve-census/`: `build_census.py`, `MCP-CVE-CENSUS.md`, `MCP-CVE-CENSUS.csv`,
  `census-metadata.json`, `raw/nvd-*.json`, build logs.
- `e2e/`: `vulnerable_mcp.py`, `drive.py`, `run-e2e.ps1`, `aegis.yaml`, `RESULTS.md`,
  `results.json`, `audit.jsonl`, and run artifacts.
- `evidence/`: Maven build logs per pass, end-to-end pass results, `verdicts.md`,
  SpotBugs logs, and the baseline logs.
- `scripts/`: the Python edit and pass helpers (`edit_*.py`, `pass6_*.py`, `fix_*.py`,
  `scanner_round2.py`, and others).
- `tests/`: copies of the new test files for review.
- `patches/aegis-2.0-security-fixes.patch`: the full reverse-appliable diff of tree 1a and 1b.
- `run-threat-model.ps1`: the verdict regeneration driver.

## 3. Workspace-root discussion documents (pending your decision)

These are our comparison and discussion notes, not product documents. You said discussion and
comparison material should not be stored as loose markdown. I have left them in place rather
than delete or move them, because one is open in your editor and removal is not reversible.
Tell me whether to delete them or move them into `aegis-internal-notes/`:

- `AEGIS-MAIN-VS-2.0-GITHUB-COMMENT.md`
- `AEGIS-MAIN-VS-2.0-REVIEW-READY-ANALYSIS-20261104.md`
