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

import java.util.Map;

/**
 * Cluster operations the Kafka adapter needs, expressed as JSON returning calls.
 *
 * <p>The interface exists so the adapter can be exercised without a broker. Implementations must
 * throw {@link IllegalStateException} when the cluster is unreachable so the circuit breaker sees a
 * real failure instead of a healthy looking placeholder body.
 *
 * @author Viquar Khan
 */
public interface KafkaClusterOps extends AutoCloseable {

    String listTopics();

    String describeTopic(String topic);

    String createTopic(String topic, int partitions);

    String alterTopicConfig(String resource, Map<String, String> configs);

    String resetOffsets(String groupId);

    String deleteRecords(String topic);

    @Override
    default void close() {
        // implementations holding a broker connection override this
    }
}
