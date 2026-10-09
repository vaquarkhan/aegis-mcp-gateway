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
package io.github.vaquarkhan.aegis.engine.spi;

import java.net.InetAddress;
import java.net.UnknownHostException;

/** DNS seam for the egress control, so tests can be deterministic and hosts can plug their resolver. */
@FunctionalInterface
public interface AddressResolver {

    AddressResolver SYSTEM = InetAddress::getAllByName;

    InetAddress[] resolveAll(String host) throws UnknownHostException;
}
