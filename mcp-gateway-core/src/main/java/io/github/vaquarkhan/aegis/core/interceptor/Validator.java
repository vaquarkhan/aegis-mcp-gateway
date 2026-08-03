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
 * Interceptor that only inspects a call and returns a decision. This is the shape of every step
 * from 1 through 9.
 *
 * @author Viquar Khan
 */
public interface Validator extends Interceptor {

    @Override
    default Phase phase() {
        return Phase.VALIDATION;
    }

    /** LLD section 4 name for {@link #apply(CallContext)}. */
    default Decision validate(CallContext ctx) {
        return apply(ctx);
    }

    /** Convenience factory for lambda based validators. */
    static Validator of(String name, int step, java.util.function.Function<CallContext, Decision> fn) {
        return new Validator() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public int step() {
                return step;
            }

            @Override
            public Decision apply(CallContext ctx) {
                return fn.apply(ctx);
            }
        };
    }
}
