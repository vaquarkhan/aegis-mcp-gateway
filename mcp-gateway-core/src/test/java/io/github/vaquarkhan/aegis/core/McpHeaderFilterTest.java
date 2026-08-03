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
package io.github.vaquarkhan.aegis.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.vaquarkhan.aegis.core.transport.McpHeaderFilter;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The streamable HTTP hint headers are advisory by default and mandatory on request, and either
 * way they must never decide anything the request body has not already said.
 *
 * @author Viquar Khan
 */
class McpHeaderFilterTest {

    @Test
    void passesThroughWhenTheHeadersAreAbsentAndNotRequired() throws Exception {
        Recorder r = call(new McpHeaderFilter(false), "POST", null, null);
        assertTrue(r.chainCalled);
        assertEquals(0, r.status);
    }

    @Test
    void passesThroughAndLogsWhenTheHeadersArePresent() throws Exception {
        Recorder r = call(new McpHeaderFilter(false), "POST", "tools/call", "list_jobs");
        assertTrue(r.chainCalled);
    }

    @Test
    void rejectsAPostWithoutTheMethodHeaderWhenRequired() throws Exception {
        Recorder r = call(new McpHeaderFilter(true), "POST", null, "list_jobs");
        assertFalse(r.chainCalled);
        assertEquals(400, r.status);
        assertTrue(r.body().contains("missing_mcp_method"));
    }

    @Test
    void acceptsAPostCarryingTheMethodHeaderWhenRequired() throws Exception {
        Recorder r = call(new McpHeaderFilter(true), "POST", "tools/list", null);
        assertTrue(r.chainCalled);
        assertEquals(0, r.status);
    }

    @Test
    void neverBlocksNonPostRequests() throws Exception {
        assertTrue(call(new McpHeaderFilter(true), "GET", null, null).chainCalled,
                "the SSE stream and session delete carry no Mcp-Method");
        assertTrue(call(new McpHeaderFilter(true), "DELETE", null, null).chainCalled);
    }

    @Test
    void treatsABlankHeaderAsAbsent() throws Exception {
        Recorder r = call(new McpHeaderFilter(true), "POST", "   ", null);
        assertFalse(r.chainCalled);
        assertEquals(400, r.status);
    }

    private static Recorder call(McpHeaderFilter filter, String httpMethod, String mcpMethod, String mcpName)
            throws Exception {

        Recorder recorder = new Recorder();
        HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(
                McpHeaderFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletRequest.class},
                (proxy, method, args) -> {
                    if ("getMethod".equals(method.getName())) {
                        return httpMethod;
                    }
                    if ("getHeader".equals(method.getName())) {
                        String name = (String) args[0];
                        if (McpHeaderFilter.HEADER_METHOD.equalsIgnoreCase(name)) {
                            return mcpMethod;
                        }
                        return McpHeaderFilter.HEADER_NAME.equalsIgnoreCase(name) ? mcpName : null;
                    }
                    return defaultFor(method);
                });
        HttpServletResponse response = (HttpServletResponse) Proxy.newProxyInstance(
                McpHeaderFilterTest.class.getClassLoader(),
                new Class<?>[] {HttpServletResponse.class},
                recorder);
        filter.doFilter(request, response, (ServletRequest rq, ServletResponse rs) -> recorder.chainCalled = true);
        return recorder;
    }

    private static Object defaultFor(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive() || void.class.equals(type)) {
            return null;
        }
        return boolean.class.equals(type) ? Boolean.FALSE : 0;
    }

    /** Captures the status, headers and body the filter writes. */
    private static final class Recorder implements InvocationHandler {

        private final Map<String, String> headers = new HashMap<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private int status;
        private boolean chainCalled;

        String body() {
            return bytes.toString(StandardCharsets.UTF_8);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            switch (method.getName()) {
                case "setStatus" -> status = (int) args[0];
                case "setHeader", "addHeader" -> headers.put((String) args[0], (String) args[1]);
                case "setContentType" -> headers.put("Content-Type", (String) args[0]);
                case "getOutputStream" -> {
                    return new ServletOutputStream() {
                        @Override
                        public boolean isReady() {
                            return true;
                        }

                        @Override
                        public void setWriteListener(WriteListener listener) {
                            // Nothing to notify: the recorder is always ready.
                        }

                        @Override
                        public void write(int b) {
                            bytes.write(b);
                        }
                    };
                }
                default -> {
                    return defaultFor(method);
                }
            }
            return null;
        }
    }
}
