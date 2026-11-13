package io.saasforge.iam.referencedata.model;

import io.saasforge.sdk.core.ResourceManifest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** snapshot 为当时覆盖；current 和 manifest 仅为查询时上下文，字典已下线时可为空。 */
public record DictionaryHistoryDetailResponse(
        long version,
        String summary,
        UUID changedBy,
        String changedByName,
        Instant changedAt,
        List<DictionaryHistoryItem> snapshot,
        TenantDictionary current,
        ResourceManifest.Dictionary manifest) {}
