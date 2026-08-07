package io.github.vaquarkhan.aegis.core.jsonschema;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Request body for a Livy batch submission.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SubmitRequest(String file, String className) {
}
