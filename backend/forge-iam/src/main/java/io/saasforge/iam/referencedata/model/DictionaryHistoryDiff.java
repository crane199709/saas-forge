package io.saasforge.iam.referencedata.model;

import java.util.List;

/** 两份覆盖快照按字典项编码比较；不合并任何当前服务声明。 */
public record DictionaryHistoryDiff(
        long from,
        long to,
        List<DictionaryHistoryItem> added,
        List<DictionaryHistoryItem> removed,
        List<Change> changed) {
    public record Change(DictionaryHistoryItem before, DictionaryHistoryItem after) {}
}
