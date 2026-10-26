package io.saasforge.sdk.web;

import java.util.List;

/** 分页或排序参数非法，映射为 400 {@code request.invalid}，逐项说明哪个参数不合法。 */
class InvalidPageQueryException extends RuntimeException {

    private final transient List<Violation> violations;

    InvalidPageQueryException(List<Violation> violations) {
        super(violations.size() + " paging parameter(s) invalid");
        this.violations = List.copyOf(violations);
    }

    List<Violation> violations() {
        return violations;
    }

    record Violation(String field, String code) {}
}
