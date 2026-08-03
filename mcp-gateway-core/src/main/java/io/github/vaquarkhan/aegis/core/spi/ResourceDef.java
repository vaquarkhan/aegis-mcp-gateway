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

import java.util.function.Function;

/**
 * Engine contributed MCP resource. Read-only by construction.
 *
 * @author Viquar Khan
 */
public record ResourceDef(
        String uri,
        String name,
        String mimeType,
        Function<CallContext, String> read,
        boolean redact) {

    public ResourceDef {
        if (uri == null || uri.isBlank()) {
            throw new IllegalArgumentException("resource uri required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("resource name required for " + uri);
        }
        if (read == null) {
            throw new IllegalArgumentException("read function required for " + uri);
        }
        mimeType = (mimeType == null || mimeType.isBlank()) ? "text/plain" : mimeType;
    }

    public static ResourceDef json(String uri, String name, Function<CallContext, String> read) {
        return new ResourceDef(uri, name, "application/json", read, true);
    }

    public static ResourceDef text(String uri, String name, Function<CallContext, String> read) {
        return new ResourceDef(uri, name, "text/plain", read, true);
    }
}
