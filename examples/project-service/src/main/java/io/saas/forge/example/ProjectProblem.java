package io.saas.forge.example;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
record ProjectProblem(String type, String title, int status, String code, String detail, String traceId,
                      List<FieldError> errors) {
    static ProjectProblem of(int status, String code, String detail, String traceId, List<FieldError> errors) {
        return new ProjectProblem("urn:saas.forge:problem:" + code.toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                org.springframework.http.HttpStatus.valueOf(status).getReasonPhrase(), status, code, detail, traceId, errors);
    }

    static String traceId(String parent) {
        return parent != null && parent.matches("[0-9a-f]{2}-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}")
                ? parent.substring(3, 35) : java.util.UUID.randomUUID().toString().replace("-", "");
    }

    record FieldError(String pointer, String code, String detail) {}
}
