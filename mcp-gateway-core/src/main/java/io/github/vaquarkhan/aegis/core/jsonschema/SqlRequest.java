package io.github.vaquarkhan.aegis.core.jsonschema;

/**
 * Request body for a SQL endpoint POST.
 */
public record SqlRequest(String sql) {
}
