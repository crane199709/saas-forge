package io.saasforge.iam.referencedata.model;

import java.time.Instant;
import java.util.UUID;

/** 字典历史持久层投影；快照由服务层解析为只读覆盖项。 */
public record DictionaryHistoryRow(long version, String summary, UUID changedBy, Instant changedAt, String snapshot) {}
