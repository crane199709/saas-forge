package io.saasforge.sdk.io;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一条已有记录在本次覆盖前的取值；只包含写入器将覆盖的字段，允许字段原值为 null。 */
public record ImportSnapshot(String recordId, Map<String, Object> values) {
    /** 拷贝字段映射，防止后续业务写入改变已经取得的旧值。 */
    public ImportSnapshot {
        if (recordId == null || recordId.isBlank() || recordId.length() > 500) {
            throw new IllegalArgumentException("Invalid import record ID");
        }
        Objects.requireNonNull(values, "values");
        if (values.isEmpty()) {
            throw new IllegalArgumentException("Import snapshot needs overwritten fields");
        }
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
