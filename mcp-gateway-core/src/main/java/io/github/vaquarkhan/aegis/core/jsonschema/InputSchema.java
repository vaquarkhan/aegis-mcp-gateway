package io.github.vaquarkhan.aegis.core.jsonschema;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import java.util.List;

/**
 * A JSON Schema {@code inputSchema} object with {@code type}, {@code properties}, and optional {@code required}.
 */
@JsonPropertyOrder({"type", "properties", "required"})
public record InputSchema(String type, SchemaProperties properties, List<String> required) {
}
