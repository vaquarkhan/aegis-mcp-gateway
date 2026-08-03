package io.github.vaquarkhan.aegis.adapter.kafka.client;

/**
 * Read-only Schema Registry lookup used by the {@code query_schema_registry} tool.
 *
 * @author Viquar Khan
 */
public interface SchemaRegistryOps {

    /**
     * Returns the latest registered version of {@code subject} as the registry reported it.
     *
     * @throws IllegalStateException when the registry is unreachable or answers with an error status
     */
    String latestVersion(String subject);
}
