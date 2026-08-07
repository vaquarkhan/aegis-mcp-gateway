package io.github.vaquarkhan.aegis.core.util;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared Jackson {@link ObjectMapper} configured for JSON Schema serialization.
 */
public final class JacksonUtils {

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(inc -> JsonInclude.Value.ALL_NON_NULL)
            .build();

    public static ObjectMapper getMapper() {
        return MAPPER;
    }

    private JacksonUtils() {
    }
}
