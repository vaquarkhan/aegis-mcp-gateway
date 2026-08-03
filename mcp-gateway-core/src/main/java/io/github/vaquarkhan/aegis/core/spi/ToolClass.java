package io.github.vaquarkhan.aegis.core.spi;

/**
 * Authority level of a tool. Drives write gating, approval requirements and validate-run-promote.
 *
 * @author Viquar Khan
 */
public enum ToolClass {

    /** Observational. Registered by default. */
    READ,

    /** Changes backend state but is recoverable. Requires the write unlock plus an approval token. */
    MUTATE,

    /** Irreversible or data-losing. Additionally requires a validate-run-promote dry-run receipt. */
    DESTRUCTIVE;

    /** True when this class is at least as privileged as {@code other}. */
    public boolean atLeast(ToolClass other) {
        return this.ordinal() >= other.ordinal();
    }

    /** True for anything that is not purely observational. */
    public boolean isWrite() {
        return this != READ;
    }
}
