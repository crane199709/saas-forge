package io.saasforge.iam.operator.model;

import java.util.UUID;

/**
 * 未删除且为 active 的 Operator。
 *
 * @param region 负责的区域，作为平台 Data Policy 的主体属性；未分配时为 null
 * @param version 当前版本，签发的 Platform Realm Token 带此版本
 */
public record ActiveOperator(UUID operatorId, String account, String displayName, String region, long version) {}
