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

package io.github.vaquarkhan.aegis.adapter.kafka.client;

/**
 * Tail sampling of a dead-letter topic. Read-only: the sampler never commits an offset.
 *
 * @author Viquar Khan
 */
public interface DlqOps {

    /**
     * Returns up to {@code maxRecords} of the newest records on {@code topic} as JSON.
     *
     * @throws IllegalStateException when the cluster is unreachable
     */
    String sampleTail(String topic, int maxRecords);
}
