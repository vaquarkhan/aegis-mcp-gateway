package io.github.vaquarkhan.aegis.core.jsonschema;

/**
 * A JSON Schema property of type {@code "string"}.
 */
public record StringProperty(String type) {
    public StringProperty() {
        this("string");
    }
}
