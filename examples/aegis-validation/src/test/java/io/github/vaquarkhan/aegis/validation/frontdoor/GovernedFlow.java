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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The governance story every adopter example proves, step for step, through whichever front door it is
 * given. The adopter test supplies the real engine and checks the real effect; this class owns the
 * order of calls and the assertions that make the story true.
 */
public final class GovernedFlow {

    /** Checks the real engine's state; throws if the expected effect is absent. */
    public interface EngineCheck {
        void verify() throws Exception;
    }

    private GovernedFlow() {}

    /**
     * A read the reader may make, and an outsider may not. Knox refuses the outsider at the route
     * (403, Aegis never runs); the own host lets Aegis refuse them (no adopter group).
     */
    public static McpReply read(FrontDoor door, String tool, Map<String, Object> args, String expectedText) throws Exception {
        McpReply ok = door.call("read by " + Persona.READER, Persona.READER, tool, args);
        assertEquals(200, ok.status(), ok.body());
        assertTrue(ok.allowed(), ok.body());
        assertTrue(ok.text().contains(expectedText), "expected '" + expectedText + "' in " + ok.text());

        McpReply outsider = door.call("same read by an outsider", Persona.OUTSIDER, tool, args);
        assertFalse(outsider.allowed(), outsider.body());
        if (FrontDoor.KNOX.equals(door.name())) {
            assertEquals(403, outsider.status(), "Knox AclsAuthz refuses a user outside the route ACL");
        }
        return ok;
    }

    /**
     * The full destructive lifecycle: refused for a reader, refused without approval, previewed, refused
     * when the requester approves their own request, independently approved, executed once, and refused
     * on replay. {@code unchanged} runs while the engine must still be untouched; {@code changed} after.
     */
    public static void destructive(FrontDoor door, String tool, Map<String, Object> args,
            EngineCheck unchanged, EngineCheck changed) throws Exception {
        approvalFlow(door, "destructive", tool, args, unchanged, changed);
    }

    /** The same lifecycle for a {@code MUTATE} tool: approval per call, with the dry run as the preview. */
    public static void mutate(FrontDoor door, String tool, Map<String, Object> args,
            EngineCheck unchanged, EngineCheck changed) throws Exception {
        approvalFlow(door, "write", tool, args, unchanged, changed);
    }

    private static void approvalFlow(FrontDoor door, String kind, String tool, Map<String, Object> args,
            EngineCheck unchanged, EngineCheck changed) throws Exception {
        McpReply byReader = door.call(kind + " by " + Persona.READER, Persona.READER, tool, args);
        assertEquals("READONLY_CALLER", byReader.code(), byReader.body());

        McpReply noApproval = door.call(kind + " without approval", Persona.OPERATOR, tool, args);
        assertEquals("APPROVAL_REQUIRED", noApproval.code(), noApproval.body());
        unchanged.verify();

        McpReply dry = door.call("dry run", Persona.OPERATOR, tool, args, Map.of("dryRun", true));
        String requestId = dry.get("approvalRequestId");
        String receipt = dry.get("dryRunReceipt");
        boolean destructive = "destructive".equals(kind);
        assertNotNull(requestId, dry.body());
        if (destructive) {
            assertNotNull(receipt, "a DESTRUCTIVE dry run issues a receipt: " + dry.body());
        } else {
            // A MUTATE needs only the per-call approval; the dry run previews and opens the request.
            assertNull(receipt, "a MUTATE dry run issues no receipt: " + dry.body());
        }
        unchanged.verify();

        McpReply self = door.approve("requester approves own request", Persona.OPERATOR, requestId);
        assertEquals(403, self.status(), "separation of duties: " + self.body());
        McpReply approved = door.approve("independent approval", Persona.APPROVER, requestId);
        assertEquals(200, approved.status(), approved.body());
        String token = door.approvalToken("requester collects the token", Persona.OPERATOR, requestId);
        assertNotNull(token);

        Map<String, Object> control = new LinkedHashMap<>();
        control.put("approvalToken", token);
        if (destructive) {
            control.put("dryRunReceipt", receipt);
        }
        McpReply run = door.call(destructive ? "execute with approval and receipt" : "execute with approval",
                Persona.OPERATOR, tool, args, control);
        assertTrue(run.allowed(), run.body());
        changed.verify();

        McpReply replay = door.call("replay of the same approval", Persona.OPERATOR, tool, args, control);
        assertFalse(replay.allowed(), "an approval token is single use: " + replay.body());
    }

    /**
     * A call that governance must refuse with {@code code} even for a caller who may use the tool,
     * with the engine untouched afterwards (for example a write smuggled into a read-only SQL tool).
     */
    public static void refused(FrontDoor door, String step, Persona who, String tool, Map<String, Object> args,
            String code, EngineCheck unchanged) throws Exception {
        McpReply r = door.call(step, who, tool, args);
        assertEquals(code, r.code(), r.body());
        unchanged.verify();
    }
}
