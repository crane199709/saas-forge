package io.saas.forge.example;

import java.util.UUID;

public record TaskResult(UUID id, UUID projectId, String title, String description, Status status,
                         long version, String createdAt, String updatedAt) {
    public enum Status {
        TODO, IN_PROGRESS, DONE;

        /** 严格匹配契约枚举，不接受序号或自动去除空白后的状态。 */
        @com.fasterxml.jackson.annotation.JsonCreator
        public static Status fromJson(String value) { return valueOf(value); }
    }
}
