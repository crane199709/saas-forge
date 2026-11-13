package io.saasforge.iam.referencedata.service;

import io.saasforge.iam.membership.model.TenantActor;
import io.saasforge.iam.referencedata.mapper.DictionaryHistoryMapper;
import io.saasforge.iam.referencedata.mapper.DictionaryItemMapper;
import io.saasforge.iam.referencedata.model.DictionaryHistoryItem;
import io.saasforge.iam.referencedata.model.DictionaryHistoryResponse;
import io.saasforge.sdk.core.UuidV7;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 字典变更历史只保存租户覆盖，服务清单的演进不会产生版本。 */
@Service
public class DictionaryHistoryService {
    private final DictionaryHistoryMapper history;
    private final DictionaryItemMapper items;
    private final JsonMapper json;

    DictionaryHistoryService(DictionaryHistoryMapper history, DictionaryItemMapper items, JsonMapper json) {
        this.history = history;
        this.items = items;
        this.json = json;
    }

    /** 读取指定字典的完整历史；调用方须先绑定可信租户上下文，并在公开入口校验查看权限。 */
    public List<DictionaryHistoryResponse> list(UUID tenantId, String service, String dictionary) {
        return history.list(tenantId, service, dictionary).stream()
                .map(row -> new DictionaryHistoryResponse(
                        row.version(),
                        row.summary(),
                        row.changedBy(),
                        row.changedAt(),
                        json.readValue(row.snapshot(), new TypeReference<List<DictionaryHistoryItem>>() {})))
                .toList();
    }

    void append(TenantActor actor, String service, String dictionary, String summary) {
        // 调用方仍持有字典锁；此时读取全部覆盖，避免快照混入其他并发修改或 Manifest 的内置默认项。
        List<DictionaryHistoryItem> snapshot = items.list(actor.tenantId()).stream()
                .filter(row -> row.service().equals(service) && row.dictionary().equals(dictionary))
                .map(row ->
                        new DictionaryHistoryItem(row.code(), row.custom(), row.name(), row.sortOrder(), row.enabled()))
                .sorted(Comparator.comparing(DictionaryHistoryItem::code))
                .toList();
        history.append(
                actor.tenantId(),
                UuidV7.generate(),
                service,
                dictionary,
                json.writeValueAsString(snapshot),
                summary,
                actor.identityId());
    }
}
