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
package io.github.vaquarkhan.aegis.core.interceptor;

/**
 * Which kind of work an interceptor performs, as in LLD section 4.
 *
 * <p>Mutation runs first and atomically, validation decides, observation only records. The older
 * position based names are retained as deprecated aliases so existing plugins keep compiling.
 *
 * @author Viquar Khan
 */
public enum Phase {

    /** Rewrites the call: argument sanitizing inbound, redaction outbound. */
    MUTATION,

    /** Decides whether the call proceeds. Steps 1 through 9. */
    VALIDATION,

    /** Records the outcome. Never changes it. */
    OBSERVATION,

    /**
     * Runs before the backend is contacted.
     *
     * @deprecated use {@link #VALIDATION}.
     */
    @Deprecated
    PRE,

    /**
     * The bounded backend invocation itself. Step 10.
     *
     * @deprecated the chain owns execution; interceptors never declare this phase.
     */
    @Deprecated
    EXECUTE,

    /**
     * Runs on the result: output bounding and redaction.
     *
     * @deprecated use {@link #MUTATION}.
     */
    @Deprecated
    POST
}
