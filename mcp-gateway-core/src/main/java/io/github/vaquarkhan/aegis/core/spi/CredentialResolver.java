/*
 * Licensed to the Aegis MCP Gateway project under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.vaquarkhan.aegis.core.spi;

import io.github.vaquarkhan.aegis.core.auth.CallerIdentity;
import java.util.Optional;

/**
 * On-behalf-of credential exchange. Lets the gateway present a caller specific outbound credential
 * instead of a single shared service account.
 *
 * <p>An empty result means "no caller specific credential", not "deny". Denial is a policy concern.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface CredentialResolver {

    Optional<OutboundCredential> resolve(CallerIdentity caller, String resource);
}
