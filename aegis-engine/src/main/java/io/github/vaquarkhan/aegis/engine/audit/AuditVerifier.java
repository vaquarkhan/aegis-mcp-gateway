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
package io.github.vaquarkhan.aegis.engine.audit;

import java.io.IOException;

/**
 * Verifies the one audit journal already selected by trusted gateway configuration.
 *
 * <p>The verifier accepts no path or caller input. This lets an authenticated administrative endpoint
 * answer whether its configured journal is intact without becoming a filesystem-reading API.
 */
@FunctionalInterface
public interface AuditVerifier {

    /** @return true when the configured audit chain is intact; throws when verification is unavailable */
    boolean verify() throws IOException;
}
