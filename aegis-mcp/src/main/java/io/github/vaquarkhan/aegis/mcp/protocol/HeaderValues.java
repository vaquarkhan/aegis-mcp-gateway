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
package io.github.vaquarkhan.aegis.mcp.protocol;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 2026-07-28 Streamable HTTP header value encoding: plain visible ASCII, or
 * {@code =?base64?<base64 of UTF-8>?=} when the value is not header-safe (non-ASCII, control
 * characters, leading/trailing whitespace, or itself looks like the sentinel).
 */
public final class HeaderValues {

    private static final String PREFIX = "=?base64?";
    private static final String SUFFIX = "?=";

    private HeaderValues() {}

    public static String encode(String value) {
        if (safe(value)) {
            return value;
        }
        return PREFIX + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + SUFFIX;
    }

    /**
     * Decodes a received header value.
     *
     * @return the decoded value, or null when the header contains invalid characters or a malformed sentinel
     */
    public static String decode(String header) {
        if (header == null) {
            return null;
        }
        if (header.startsWith(PREFIX) && header.endsWith(SUFFIX) && header.length() >= PREFIX.length() + SUFFIX.length()) {
            String b64 = header.substring(PREFIX.length(), header.length() - SUFFIX.length());
            try {
                byte[] raw = Base64.getDecoder().decode(b64);
                return StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(raw)).toString();
            } catch (IllegalArgumentException | CharacterCodingException e) {
                return null;
            }
        }
        for (int i = 0; i < header.length(); i++) {
            char c = header.charAt(i);
            if (!(c == 0x09 || (c >= 0x20 && c <= 0x7e))) {
                return null;
            }
        }
        return header;
    }

    private static boolean safe(String v) {
        if (v.isEmpty()) {
            return true;
        }
        if (v.startsWith(PREFIX) && v.endsWith(SUFFIX)) {
            return false;
        }
        if (Character.isWhitespace(v.charAt(0)) || Character.isWhitespace(v.charAt(v.length() - 1))) {
            return false;
        }
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                return false;
            }
        }
        return true;
    }
}
