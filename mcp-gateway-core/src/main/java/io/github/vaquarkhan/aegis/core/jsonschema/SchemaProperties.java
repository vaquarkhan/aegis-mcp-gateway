package io.github.vaquarkhan.aegis.core.jsonschema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Strongly-typed JSON Schema {@code properties} object.
 * Each field is an optional property definition; null fields are omitted from serialized output.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SchemaProperties(
        StringProperty appId,
        StringProperty approvalToken,
        StringProperty className,
        StringProperty collection,
        StringProperty file,
        StringProperty q,
        StringProperty sql) {

    private static final SchemaProperties EMPTY = new SchemaProperties(null, null, null, null, null, null, null);

    public static SchemaProperties empty() {
        return EMPTY;
    }

    public static SchemaProperties appIdOnly() {
        return new SchemaProperties(new StringProperty(), null, null, null, null, null, null);
    }

    public static SchemaProperties appIdAndApprovalToken() {
        return new SchemaProperties(new StringProperty(), new StringProperty(), null, null, null, null, null);
    }

    public static SchemaProperties collectionAndApprovalToken() {
        return new SchemaProperties(null, new StringProperty(), null, new StringProperty(), null, null, null);
    }

    public static SchemaProperties collectionAndQ() {
        return new SchemaProperties(null, null, null, new StringProperty(), null, new StringProperty(), null);
    }

    public static SchemaProperties fileClassNameAndApprovalToken() {
        return new SchemaProperties(null, new StringProperty(), new StringProperty(), null, new StringProperty(), null, null);
    }

    public static SchemaProperties sqlOnly() {
        return new SchemaProperties(null, null, null, null, null, null, new StringProperty());
    }
}
