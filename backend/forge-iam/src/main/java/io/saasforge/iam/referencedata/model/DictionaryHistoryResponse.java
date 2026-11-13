package io.saasforge.iam.referencedata.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** 一次有效字典变更及当时的完整租户覆盖集合，变更时间与变更人取自创建标准列。 */
public record DictionaryHistoryResponse(
        long version, String summary, UUID changedBy, Instant changedAt, List<DictionaryHistoryItem> snapshot) {
    public DictionaryHistoryResponse {
        snapshot = List.copyOf(snapshot);
    }
}
