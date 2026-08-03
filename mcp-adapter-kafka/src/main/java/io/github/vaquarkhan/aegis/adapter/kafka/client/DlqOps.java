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
