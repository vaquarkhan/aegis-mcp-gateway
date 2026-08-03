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

/**
 * Authority level of a tool. Drives write gating, approval requirements and validate-run-promote.
 *
 * @author Viquar Khan
 */
public enum ToolClass {

    /** Observational. Registered by default. */
    READ,

    /** Changes backend state but is recoverable. Requires the write unlock plus an approval token. */
    MUTATE,

    /** Irreversible or data-losing. Additionally requires a validate-run-promote dry-run receipt. */
    DESTRUCTIVE;

    /** True when this class is at least as privileged as {@code other}. */
    public boolean atLeast(ToolClass other) {
        return this.ordinal() >= other.ordinal();
    }

    /** True for anything that is not purely observational. */
    public boolean isWrite() {
        return this != READ;
    }
}
