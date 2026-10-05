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
package io.github.vaquarkhan.aegis.engine.model;

/**
 * Authority level of a tool. Same names and ordering as Aegis 0.1 {@code core.spi.ToolClass}, so
 * audit records, dashboards and operator configuration stay compatible.
 */
public enum ToolClass {

    /** Observational. */
    READ,

    /** Changes backend state but is recoverable. Requires write unlock and an approval token. */
    MUTATE,

    /** Irreversible or data-losing. Additionally requires a validate-run-promote dry-run receipt. */
    DESTRUCTIVE;

    public boolean atLeast(ToolClass other) {
        return ordinal() >= other.ordinal();
    }

    public boolean isWrite() {
        return this != READ;
    }

    /** The more privileged of two classes. Used so overlays and hints can only raise a class. */
    public static ToolClass max(ToolClass a, ToolClass b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    public static ToolClass parse(String s) {
        if (s == null) {
            throw new IllegalArgumentException("tool class must not be null");
        }
        return switch (s.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "READ" -> READ;
            case "MUTATE" -> MUTATE;
            case "DESTRUCTIVE" -> DESTRUCTIVE;
            default -> throw new IllegalArgumentException("unknown tool class '" + s + "' (READ|MUTATE|DESTRUCTIVE)");
        };
    }
}
