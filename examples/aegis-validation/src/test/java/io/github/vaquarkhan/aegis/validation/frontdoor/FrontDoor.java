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
package io.github.vaquarkhan.aegis.validation.frontdoor;

import io.github.vaquarkhan.aegis.engine.json.Json;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;

/**
 * A way in to Aegis that a real MCP client would use: Streamable HTTP, MCP 2026-07-28 headers, and the
 * front door's own authentication. Subclasses differ only in where the endpoint lives and how a persona
 * authenticates; every request and response is appended to a plain-text transcript, which is the
 * result log kept with each adopter example.
 */
public abstract class FrontDoor implements AutoCloseable {

    public static final String OWN_HOST = "own-host";
    public static final String KNOX = "knox";

    private static final String AEGIS = "io.github.vaquarkhan.aegis/";

    protected final AdopterScenario scenario;
    protected final Path workDir;
    private final Path transcript;
    private final HttpClient http;
    private final String testClass = RunRecord.callerTestClass();
    private final java.util.List<String> steps = new java.util.ArrayList<>();
    private String firstTool;
    private String firstBody;
    private int id;

    protected FrontDoor(AdopterScenario scenario, Path workDir, HttpClient http) throws IOException {
        this.scenario = scenario;
        this.workDir = workDir;
        this.http = http;
        Path results = Path.of(System.getProperty("aegis.results.dir", "target/adopter-results"), scenario.adopter());
        Files.createDirectories(results);
        this.transcript = results.resolve(name() + "-transcript.log");
        Files.writeString(transcript, "# " + scenario.adopter() + " through " + name() + ", " + Instant.now() + "\n",
                StandardCharsets.UTF_8);
    }

    /**
     * Opens the scenario behind the named front door. The Knox door is skipped, never passed, when no
     * Knox 3.0.0 installation is configured.
     */
    public static FrontDoor open(String door, AdopterScenario scenario, Path workDir) throws Exception {
        return switch (door) {
            case OWN_HOST -> new OwnHostFrontDoor(scenario, workDir);
            case KNOX -> {
                Path home = KnoxRuntime.home();
                Assumptions.assumeTrue(home != null, "set AEGIS_KNOX_HOME to an unpacked Apache Knox 3.0.0 "
                        + "distribution (examples/adopters/knox/install-knox.ps1 or .sh) to run the Knox front door");
                yield new KnoxFrontDoor(scenario, workDir, home);
            }
            default -> throw new IllegalArgumentException(door);
        };
    }

    public abstract String name();

    protected abstract URI mcpUri();

    protected abstract URI adminUri(String path);

    protected abstract String authorization(Persona who);

    /** Where this front door's Aegis audit journal is, so it can be kept with the transcript. */
    public abstract Path auditJournal();

    /** A governed {@code tools/call}. {@code control} holds Aegis control values (dry run, approval, receipt). */
    public McpReply call(String step, Persona who, String tool, Map<String, Object> args, Map<String, Object> control)
            throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("io.modelcontextprotocol/protocolVersion", "2026-07-28");
        meta.put("io.modelcontextprotocol/clientCapabilities", Map.of());
        control.forEach((k, v) -> meta.put(AEGIS + k, v));
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", tool);
        params.put("arguments", args);
        params.put("_meta", meta);
        Map<String, Object> body = Map.of("jsonrpc", "2.0", "id", (long) ++id, "method", "tools/call", "params", params);
        HttpRequest.Builder b = HttpRequest.newBuilder(mcpUri()).timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .header("MCP-Protocol-Version", "2026-07-28")
                .header("Mcp-Method", "tools/call")
                .header("Mcp-Name", tool)
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        if (firstTool == null) {
            firstTool = tool;
            firstBody = Json.write(body);
        }
        return send(step, who, "tools/call " + tool + " " + Json.write(args), b);
    }

    public McpReply call(String step, Persona who, String tool, Map<String, Object> args) throws Exception {
        return call(step, who, tool, args, Map.of());
    }

    /** {@code POST approvals/{id}/approve} on the admin API, as the given persona. */
    public McpReply approve(String step, Persona who, String requestId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(adminUri("approvals/" + requestId + "/approve"))
                .timeout(Duration.ofSeconds(30)).POST(HttpRequest.BodyPublishers.noBody());
        return send(step, who, "POST approvals/" + requestId + "/approve", b);
    }

    /** {@code GET catalog} on the admin API: every tool with its source, definition digest and status. */
    public McpReply catalog(String step, Persona who) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(adminUri("catalog")).timeout(Duration.ofSeconds(30)).GET();
        return send(step, who, "GET catalog", b);
    }

    /** The single-use approval token the requester collects after an independent approval. */
    public String approvalToken(String step, Persona requester, String requestId) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(adminUri("approvals/" + requestId))
                .timeout(Duration.ofSeconds(30)).GET();
        McpReply r = send(step, requester, "GET approvals/" + requestId, b);
        if (r.status() != 200) {
            throw new AssertionError("approval view failed: " + r.status() + " " + r.body());
        }
        Object token = Json.parseObject(r.body()).get("token");
        return token == null ? null : token.toString();
    }

    private McpReply send(String step, Persona who, String what, HttpRequest.Builder b) throws Exception {
        String auth = authorization(who);
        if (auth != null) {
            b.header("Authorization", auth);
        }
        HttpResponse<String> rsp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        McpReply reply = new McpReply(rsp.statusCode(), rsp.body());
        String outcome = reply.decision().isEmpty() ? "" : " decision=" + reply.decision().get("outcome");
        steps.add("| " + (steps.size() + 1) + " | " + step + " | `" + who.user() + "` (" + who.name().toLowerCase(java.util.Locale.ROOT)
                + ") | `" + RunRecord.portable(what).replace("|", "\\|") + "` | HTTP " + rsp.statusCode()
                + (outcome.isEmpty() ? "" : ", `" + reply.decision().get("outcome") + "`") + " |");
        Files.writeString(transcript, "\n=== " + step + " | " + who.user() + " (" + who + ") | " + what
                + " | HTTP " + rsp.statusCode() + outcome + "\n" + redact(rsp.body()) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        return reply;
    }

    /** Approval tokens and receipts are bearer secrets; the kept log shows that they existed, not their value. */
    private static String redact(String body) {
        if (body == null) {
            return "";
        }
        return body.replaceAll("\"(token|dryRunReceipt)\":\"[^\"]+\"", "\"$1\":\"<redacted>\"");
    }

    /** Records a note in the transcript (for example, what the real engine reported afterwards). */
    public void note(String text) throws IOException {
        Files.writeString(transcript, "\n--- " + text + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    /** Copies the audit journal next to the transcript; called after the gateway has written it. */
    protected void keepAudit() throws IOException {
        Path audit = auditJournal();
        if (audit != null && Files.isRegularFile(audit)) {
            Files.copy(audit, transcript.resolveSibling(name() + "-audit.jsonl"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        recordRun();
    }

    /**
     * This door's part of {@code HOW-TO-RUN.md}: how to start it by hand with the files it used, one example
     * request, and the steps the test sent. Subclasses supply the start-up instructions.
     */
    protected abstract String startInstructions();

    /** A curl command equivalent to the first tools/call this door sent (the reader's first read). */
    protected abstract String curlFirstCall(String tool, String jsonBody);

    private void recordRun() throws IOException {
        String adopter = scenario.adopter();
        RunRecord.write(adopter, "how-to-run/engine.md", RunRecord.enginesSection(testClass, adopter));
        StringBuilder md = new StringBuilder();
        md.append(OWN_HOST.equals(name()) ? "## 2. Without Knox: Aegis's own server\n\n" : "## 3. With Knox: Aegis inside Apache Knox 3.0.0\n\n")
                .append(startInstructions()).append('\n');
        if (firstTool != null) {
            md.append("A request, the same as the test's first one (`").append(firstTool).append("` as `")
                    .append(Persona.READER.user()).append("`):\n\n```bash\n")
                    .append(curlFirstCall(firstTool, RunRecord.portable(firstBody))).append("\n```\n\n")
                    .append("Approvals use the admin API on the same door: `POST <admin>/approvals/<id>/approve` as an approver, ")
                    .append("then `GET <admin>/approvals/<id>` as the requester returns the single-use token. A call carries ")
                    .append("Aegis controls in `params._meta`: `io.github.vaquarkhan.aegis/dryRun`, `.../approvalToken` and ")
                    .append("`.../dryRunReceipt`.\n\n");
        }
        md.append("What the test sent through this door, in order, and what came back:\n\n")
                .append("| # | Step | Caller | Request | Result |\n| --- | --- | --- | --- | --- |\n");
        steps.forEach(s -> md.append(s).append('\n'));
        RunRecord.write(adopter, "how-to-run/" + name() + ".md", md.toString());
        RunRecord.assemble(adopter, testClass);
    }
}
