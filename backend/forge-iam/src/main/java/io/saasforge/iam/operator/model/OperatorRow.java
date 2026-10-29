package io.saasforge.iam.operator.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Operator 查询投影，不含凭证。
 *
 * @param region 负责的区域，未分配时为 null
 * @param status {@code active} 或 {@code disabled}
 */
public record OperatorRow(
        UUID id, String account, String displayName, String region, String status, OffsetDateTime createdAt) {}
