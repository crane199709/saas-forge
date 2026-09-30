package io.saas.forge.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ProjectProblem(String type, String title, int status, String code, String detail, String traceId,
                      List<FieldError> errors) {
    record FieldError(String pointer, String code, String detail) {}
}
