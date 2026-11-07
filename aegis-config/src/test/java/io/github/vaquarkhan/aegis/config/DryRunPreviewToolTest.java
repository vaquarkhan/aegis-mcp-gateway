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
package io.github.vaquarkhan.aegis.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.engine.CallOutcome;
import io.github.vaquarkhan.aegis.engine.json.Json;
import io.github.vaquarkhan.aegis.engine.model.CallerIdentity;
import io.github.vaquarkhan.aegis.engine.model.ToolCall;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dryRun: {mode: TOOL}} across every source shape, and the rule that a preview may not write.
 *
 * <p>Mode TOOL means "validate by running this other, read-only tool against the exact same target".
 * Two things have to hold for that to be worth anything: the preview must really be the other tool's
 * operation, not the destructive one relabelled, and the preview must not be able to change anything.
 * Both are enforced when the configuration is loaded, so a deployment cannot start with a dry run that
 * is secretly the real call.
 */
class DryRunPreviewToolTest {

    @TempDir
    Path dir;

    private static final Map<String, String> ENV = Map.of("AEGIS_SIGNING_KEY", "k".repeat(40));

    private BuildContext ctx() {
        return BuildContext.defaults(dir).withEnv(ENV::get);
    }

    /** A caller who is in the write group, so a dry run gets past the read-only check. */
    private static final CallerIdentity OPERATOR =
            CallerIdentity.of("oscar", Set.of("ops"), "idp", "jwt");

    @BeforeEach
    void resetCounters() {
        PreviewPluginToolSourceFactory.reset();
    }

    private static String pluginConfig(String toolsJson, String settingsJson) {
        return """
                {"access":{"writeGroups":["ops"],"approverGroups":["appr"]},
                 "approval":{"keyEnv":"AEGIS_SIGNING_KEY"},
                 "policy":{"rules":[{"id":"r","groups":["ops"],"classes":["READ","MUTATE","DESTRUCTIVE"]}]},
                 "audit":{"file":"a.jsonl"},
                 "sources":[{"id":"p","type":"preview-plugin","settings":{%s},"tools":%s}]}"""
                .formatted(settingsJson, toolsJson);
    }

    /**
     * The case this was built for: a converter-shaped source, one executor per tool, no dispatch by
     * upstream name. The preview has to resolve to the source's own {@code peek} tool.
     *
     * <p>The assertion that matters is the call count. A dry run must run {@code peek} once and
     * {@code wipe} not at all; if the preview fell back to the calling tool's own executor, {@code wipe}
     * would have run and the "validate" phase would have destroyed the thing it was validating.
     */
    @Test
    void aPluginSourcesPreviewRunsTheReadToolAndNotTheDestructiveOne() throws Exception {
        Map<String, Object> cfg = Json.parseObject(pluginConfig("""
                {"peek":{"class":"READ"},
                 "wipe":{"class":"DESTRUCTIVE","dryRun":{"mode":"TOOL","previewTool":"peek"}}}""", ""));
        try (Gateway g = GatewayFactory.build(cfg, ctx())) {
            CallOutcome dry = g.engine().call(OPERATOR,
                    new ToolCall("1", "wipe", Map.of("dryRun", true), Map.of(), ""));
            assertTrue(dry.allowed(), dry.decision().toString());
            assertEquals(1, PreviewPluginToolSourceFactory.calls("peek"), "the preview tool ran");
            assertEquals(0, PreviewPluginToolSourceFactory.calls("wipe"),
                    "a dry run must not run the destructive tool");
        }
    }

    /**
     * A preview naming a tool the source does not actually have is a startup failure, not a runtime
     * surprise. {@code glance} is declared and classified here, so the config looks well formed; it is
     * the source's own tool list that does not contain it.
     */
    @Test
    void aPreviewNamingAToolTheSourceDoesNotHaveIsRejectedAtStartup() {
        Map<String, Object> cfg = Json.parseObject(pluginConfig("""
                {"peek":{"class":"READ"},"glance":{"class":"READ"},
                 "wipe":{"class":"DESTRUCTIVE","dryRun":{"mode":"TOOL","previewTool":"glance"}}}""", ""));
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(cfg, ctx()));
        assertTrue(message(e).contains("not a tool of this source"), message(e));
    }

    /**
     * A preview that is not classified in the config at all is refused too. Otherwise the promise
     * "validating cannot change anything" would rest on whatever the source default happens to be, which
     * is written somewhere else in the file and can change without anyone revisiting this tool.
     */
    @Test
    void aPreviewWithNoExplicitClassIsRejectedAtStartup() {
        Map<String, Object> cfg = Json.parseObject(pluginConfig("""
                {"peek":{},
                 "wipe":{"class":"DESTRUCTIVE","dryRun":{"mode":"TOOL","previewTool":"peek"}}}""", ""));
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(cfg, ctx()));
        assertTrue(message(e).contains("explicit class"), message(e));
    }

    /**
     * A preview that is itself a write would make "validate" the operation. Rejected for every source
     * type, so the guarantee holds wherever mode TOOL is used.
     */
    @Test
    void aPreviewThatIsNotClassifiedReadIsRejectedAtStartup() {
        Map<String, Object> plugin = Json.parseObject(pluginConfig("""
                {"peek":{"class":"MUTATE"},
                 "wipe":{"class":"DESTRUCTIVE","dryRun":{"mode":"TOOL","previewTool":"peek"}}}""", ""));
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(plugin, ctx()));
        assertTrue(message(e).contains("classified READ"), message(e));

        Map<String, Object> http = Json.parseObject("""
                {"access":{"writeGroups":["ops"],"approverGroups":["appr"]},
                 "approval":{"keyEnv":"AEGIS_SIGNING_KEY"},
                 "policy":{"rules":[{"id":"r","groups":["ops"]}]},"audit":{"file":"b.jsonl"},
                 "egress":{"allowCidrs":["127.0.0.0/8"]},
                 "sources":[{"id":"s","type":"http","baseUrl":"http://127.0.0.1:1","tools":[
                   {"name":"touch","description":"d","class":"MUTATE","request":{"method":"POST","path":"/t"}},
                   {"name":"drop","description":"d","class":"DESTRUCTIVE","request":{"method":"DELETE","path":"/x"},
                    "dryRun":{"mode":"TOOL","previewTool":"touch"}}]}]}""");
        Exception he = assertThrows(Exception.class, () -> GatewayFactory.build(http, ctx()));
        assertTrue(message(he).contains("classified READ"), message(he));
    }

    /** A source that cannot be asked for its tools fails closed rather than starting without the preview. */
    @Test
    void aSourceThatCannotListItsToolsFailsClosed() {
        Map<String, Object> cfg = Json.parseObject(pluginConfig("""
                {"peek":{"class":"READ"},
                 "wipe":{"class":"DESTRUCTIVE","dryRun":{"mode":"TOOL","previewTool":"peek"}}}""",
                "\"failDiscovery\":true"));
        Exception e = assertThrows(Exception.class, () -> GatewayFactory.build(cfg, ctx()));
        assertTrue(message(e).contains("cannot list its tools"), message(e));
    }

    private static String message(Exception e) {
        return e.getMessage() + " | cause: " + e.getCause();
    }
}
