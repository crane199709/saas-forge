package io.saasforge.platform.subapp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import io.saasforge.platform.subapp.entity.SubApp;
import io.saasforge.platform.subapp.mapper.SubAppMapper;
import io.saasforge.platform.subapp.model.SubAppDetails;
import io.saasforge.platform.subapp.model.SubAppRow;
import io.saasforge.sdk.core.UuidV7;
import io.saasforge.sdk.core.api.ForgeException;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.SubAppChanged;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * App Registry（规格第 12 节）的维护：Sub-App 编码、各环境 {@code remoteEntry} 地址、版本与所需最低共享依赖版本。
 *
 * <p>每次变更在同一事务中经 Outbox 发布 {@link SubAppChanged}，iam 据此维护副本并向 Shell App 下发可加载的 Sub-App。
 * 切换 Sub-App 版本只需修改入口地址，Shell App 无需重新发布。
 */
@Service
public class SubAppService {
    private final SubAppMapper apps;
    private final EventPublisher events;

    SubAppService(SubAppMapper apps, EventPublisher events) {
        this.apps = apps;
        this.events = events;
    }

    /** 全部未删除的 Sub-App，按编码排序。 */
    public List<SubAppDetails> list() {
        return apps.selectList(new LambdaQueryWrapper<SubApp>().orderByAsc(SubApp::getCode)).stream()
                .map(this::details)
                .toList();
    }

    /**
     * 登记 Sub-App 并广播。
     *
     * @throws ForgeException 409 {@code subApp.codeTaken}
     */
    @Transactional
    public SubAppDetails create(
            String code,
            String name,
            String version,
            Map<String, String> remoteEntries,
            Map<String, String> sharedRequirements) {
        var app = new SubApp();
        app.setId(UuidV7.generate());
        app.setCode(code);
        app.setName(name);
        app.setVersion(version);
        app.setRevision(apps.nextRevision());
        try {
            apps.insert(app);
        } catch (DuplicateKeyException e) {
            throw new ForgeException(SubAppErrorCode.CODE_TAKEN, code);
        }
        writeDetails(app.getId(), remoteEntries, sharedRequirements);
        return publish(app.getId(), false);
    }

    /** 整体替换名称、版本、入口地址与最低共享依赖版本（编码不可修改）并广播；Shell App 刷新后按新地址加载。 */
    @Transactional
    public SubAppDetails update(
            UUID id,
            String name,
            String version,
            Map<String, String> remoteEntries,
            Map<String, String> sharedRequirements) {
        SubApp app = apps.lockExclusive(id);
        if (app == null) throw notFound(id);
        app.setName(name);
        app.setVersion(version);
        app.setRevision(apps.nextRevision());
        apps.updateById(app);
        apps.clearEntries(id);
        apps.clearRequirements(id);
        writeDetails(id, remoteEntries, sharedRequirements);
        return publish(id, false);
    }

    /**
     * 删除 Sub-App（逻辑删除）并广播，Shell App 随即不再加载它。Plan 的 Feature Pack 中仍可保留该编码，重新登记后随之恢复。
     */
    @Transactional
    public void delete(UUID id) {
        SubApp app = apps.lockExclusive(id);
        if (app == null) throw notFound(id);
        app.setRevision(apps.nextRevision());
        apps.updateById(app);
        // 逻辑删除前读出内容随事件发布；deleteById 之后按主键读不到已删除的行
        SubAppDetails details = details(app);
        apps.deleteById(id);
        events.publish(SubAppChanged.TOPIC, event(details, true));
    }

    private void writeDetails(UUID id, Map<String, String> remoteEntries, Map<String, String> sharedRequirements) {
        if (!remoteEntries.isEmpty()) apps.insertEntries(id, rows(remoteEntries));
        if (!sharedRequirements.isEmpty()) apps.insertRequirements(id, rows(sharedRequirements));
    }

    // 无租户上下文发布：App Registry 是平台数据，变更对所有租户生效。
    private SubAppDetails publish(UUID id, boolean removed) {
        SubAppDetails details = details(apps.selectById(id));
        events.publish(SubAppChanged.TOPIC, event(details, removed));
        return details;
    }

    private SubAppDetails details(SubApp app) {
        return new SubAppDetails(app, map(apps.entries(app.getId())), map(apps.requirements(app.getId())));
    }

    private static SubAppChanged event(SubAppDetails details, boolean removed) {
        SubApp app = details.app();
        return new SubAppChanged(
                app.getCode(),
                app.getName(),
                app.getVersion(),
                details.remoteEntries(),
                details.sharedRequirements(),
                removed,
                app.getRevision());
    }

    private static List<SubAppRow> rows(Map<String, String> values) {
        return new TreeMap<>(values)
                .entrySet().stream()
                        .map(entry -> new SubAppRow(entry.getKey(), entry.getValue()))
                        .toList();
    }

    private static SortedMap<String, String> map(List<SubAppRow> rows) {
        var result = new TreeMap<String, String>();
        for (SubAppRow row : rows) result.put(row.key(), row.value());
        return result;
    }

    private static ForgeException notFound(UUID id) {
        return ForgeException.withErrorMessage(SubAppErrorCode.NOT_FOUND, "Sub-App " + id + " not found");
    }
}
