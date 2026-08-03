package io.github.vaquarkhan.aegis.core.spi;

/**
 * Engine supplied assertion that a statement performs no writes.
 *
 * <p>Implementations must fail closed: anything they cannot prove read-only returns {@code false}.
 *
 * @author Viquar Khan
 */
@FunctionalInterface
public interface ReadOnlyGuard {

    boolean isReadOnly(String statement);
}
