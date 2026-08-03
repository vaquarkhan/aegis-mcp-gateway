package io.github.vaquarkhan.aegis.core.spi;

import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Engine contributed tool definition. Carries no MCP SDK types so adapters stay testable without
 * a running server.
 *
 * <p>{@code backend} must throw {@code Inputs.InvalidInput} for argument validation failures so the
 * interceptor chain can classify them as caller errors rather than backend errors.
 *
 * @author Viquar Khan
 */
public record ToolDef(
        String name,
        ToolClass cls,
        String description,
        String inputSchemaJson,
        Function<CallContext, String> backend) {

    /** Tool names are restricted so they are safe as metric labels and audit fields. */
    public static final Pattern NAME_PATTERN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final String EMPTY_SCHEMA = "{\"type\":\"object\",\"properties\":{}}";

    public ToolDef {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid tool name: " + name);
        }
        if (cls == null) {
            throw new IllegalArgumentException("tool class required for " + name);
        }
        if (backend == null) {
            throw new IllegalArgumentException("backend required for " + name);
        }
        description = description == null ? "" : description;
        inputSchemaJson = (inputSchemaJson == null || inputSchemaJson.isBlank())
                ? EMPTY_SCHEMA
                : inputSchemaJson;
    }

    public static ToolDef read(
            String name, String description, String inputSchemaJson, Function<CallContext, String> backend) {
        return new ToolDef(name, ToolClass.READ, description, inputSchemaJson, backend);
    }

    public static ToolDef mutate(
            String name, String description, String inputSchemaJson, Function<CallContext, String> backend) {
        return new ToolDef(name, ToolClass.MUTATE, description, inputSchemaJson, backend);
    }

    public static ToolDef destructive(
            String name, String description, String inputSchemaJson, Function<CallContext, String> backend) {
        return new ToolDef(name, ToolClass.DESTRUCTIVE, description, inputSchemaJson, backend);
    }

    /** Returns a copy under a different name, used by the aggregator to resolve collisions. */
    public ToolDef renamed(String newName) {
        return new ToolDef(newName, cls, description, inputSchemaJson, backend);
    }

    /**
     * Returns a copy at a lower authority. A YAML overlay may only reduce authority, never raise it.
     */
    public ToolDef downgradedTo(ToolClass lower) {
        if (lower == null || lower.atLeast(cls)) {
            return this;
        }
        return new ToolDef(name, lower, description, inputSchemaJson, backend);
    }
}
