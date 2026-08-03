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

import io.github.vaquarkhan.aegis.core.spi.CallContext;

/**
 * One governance step. Implementations must be side-effect free apart from their own accounting,
 * because the chain may stop before later steps run.
 *
 * @author Viquar Khan
 */
public interface Interceptor {

    String name();

    default Phase phase() {
        return Phase.VALIDATION;
    }

    /** Relative order within a phase, lowest first. Ties keep registration order. */
    default int priority() {
        return 100;
    }

    /** Step number this interceptor reports on denial. */
    int step();

    Decision apply(CallContext ctx);
}
