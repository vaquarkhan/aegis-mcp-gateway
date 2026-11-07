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

import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;

/**
 * Setup outside Aegis, as an operator's deployment pipeline would do it: waits until the real Kafka broker
 * answers, then creates the {@code orders} topic if it does not exist. Prints {@code READY topics=[...]}.
 *
 * <p>Run with Java's single-file source launcher: {@code java @kafka.args KafkaSeed.java <bootstrap>}.
 */
public final class KafkaSeed {

    public static void main(String[] args) throws Exception {
        Properties p = new Properties();
        p.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, args[0]);
        p.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 5_000);
        p.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 10_000);
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (true) {
            try (Admin a = Admin.create(p)) {
                Set<String> topics = a.listTopics().names().get();
                if (!topics.contains("orders")) {
                    a.createTopics(List.of(new NewTopic("orders", 1, (short) 1))).all().get();
                }
                // The broker's metadata cache can lag the create; poll its own view until the topic shows.
                while (!a.listTopics().names().get().contains("orders")) {
                    Thread.sleep(200);
                }
                System.out.println("READY topics=" + a.listTopics().names().get());
                return;
            } catch (Exception e) {
                if (System.nanoTime() > deadline) {
                    throw e;
                }
                Thread.sleep(1000);
            }
        }
    }
}
