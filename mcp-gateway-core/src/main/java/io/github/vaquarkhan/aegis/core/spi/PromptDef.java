package io.github.vaquarkhan.aegis.core.spi;


import java.util.Objects;

/**
 * Definition of an engine-specific prompt or system instruction template.
 * Passed back via EngineAdapter.prompts() to provide dialect & operational guidance to LLMs.
 */
public final class PromptDef {
    private final String id;
    private final String description;
    private final String template;

    public PromptDef(
            String id,
            String description,
            String template
    ) {
        this.id = id;
        this.description = description;
        this.template = template;
    }

    public String id() {
        return id;
    }

    public String description() {
        return description;
    }

    public String template() {
        return template;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (PromptDef) obj;
        return Objects.equals(this.id, that.id) &&
                Objects.equals(this.description, that.description) &&
                Objects.equals(this.template, that.template);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, description, template);
    }

    @Override
    public String toString() {
        return "PromptDef[" +
                "id=" + id + ", " +
                "description=" + description + ", " +
                "template=" + template + ']';
    }

    public PromptDef(String id, String description) {
        this(id, description, "");
    }
}
