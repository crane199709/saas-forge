package io.saasforge.iam.subapp.service;

import io.saasforge.iam.subapp.config.SubAppProperties;
import io.saasforge.iam.subapp.mapper.SubAppMapper;
import io.saasforge.iam.subapp.model.LoadableSubApp;
import io.saasforge.iam.subapp.model.SubAppDetailRow;
import io.saasforge.iam.subapp.model.SubAppRequirementRow;
import io.saasforge.sdk.provisioning.SubAppChanged;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Service;

/**
 * iam 中 App Registry 的副本：主数据属于 platform，iam 只保存向 Shell App 下发可加载 Sub-App 所需的内容。
 * 入口地址按 {@link SubAppProperties#environment()} 选取当前环境的一个。
 */
@Service
public class SubAppService {
    private final SubAppMapper apps;
    private final SubAppProperties properties;

    SubAppService(SubAppMapper apps, SubAppProperties properties) {
        this.apps = apps;
        this.properties = properties;
    }

    /** 应用 platform 广播的变更，只应用比已应用序号更新的变更，返回是否应用。 */
    public boolean apply(SubAppChanged event) {
        if (apps.apply(event.code(), event.name(), event.version(), event.revision(), event.removed()) == 0) {
            return false;
        }
        apps.clearEntries(event.code());
        apps.clearRequirements(event.code());
        if (!event.remoteEntries().isEmpty()) apps.insertEntries(event.code(), rows(event.remoteEntries()));
        if (!event.sharedRequirements().isEmpty()) {
            apps.insertRequirements(event.code(), rows(event.sharedRequirements()));
        }
        return true;
    }

    /** 给定编码中已登记、且有当前环境入口地址的 Sub-App，按编码排序；未登记或已删除的编码被略去。 */
    public List<LoadableSubApp> loadable(Collection<String> codes) {
        if (codes.isEmpty()) return List.of();
        Map<String, Map<String, String>> requirements = new HashMap<>();
        for (SubAppRequirementRow row : apps.requirements(codes)) {
            requirements.computeIfAbsent(row.code(), key -> new TreeMap<>()).put(row.dependency(), row.minVersion());
        }
        return apps.loadable(codes, properties.environment()).stream()
                .map(app -> new LoadableSubApp(
                        app.code(),
                        app.name(),
                        app.version(),
                        app.remoteEntry(),
                        requirements.getOrDefault(app.code(), Map.of())))
                .toList();
    }

    private static List<SubAppDetailRow> rows(Map<String, String> values) {
        return new TreeMap<>(values)
                .entrySet().stream()
                        .map(entry -> new SubAppDetailRow(entry.getKey(), entry.getValue()))
                        .toList();
    }
}
