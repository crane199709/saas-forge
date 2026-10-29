package io.saasforge.sdk.provisioning;

import java.util.Map;

/**
 * App Registry 中的一个 Sub-App 已登记、修改或删除：platform 在同一事务中经 Outbox 发布（Topic {@link #TOPIC}），不带租户上下文。
 *
 * <p>消息带变更后的完整登记内容而不是差量，订阅方直接整体替换本地副本。消息可能乱序或重复送达，订阅方按 {@code revision}
 * 只应用比已知更新的变更；删除后以同一编码重新登记会得到更大的 {@code revision}。
 *
 * @param code Sub-App 编码，Shell 将其路由挂在 {@code /apps/<code>} 下
 * @param name 名称
 * @param version 当前登记的版本，仅用于展示与排查
 * @param remoteEntries 各环境的 {@code remoteEntry.js} 地址，键为环境名
 * @param sharedRequirements 所需的最低共享依赖版本，键为包名，值为 {@code x.y.z}
 * @param removed 是否已从 App Registry 删除
 * @param revision 全局递增的变更序号
 */
public record SubAppChanged(
        String code,
        String name,
        String version,
        Map<String, String> remoteEntries,
        Map<String, String> sharedRequirements,
        boolean removed,
        long revision) {
    /** App Registry 相关事件所在的 Topic。 */
    public static final String TOPIC = "forge-app";

    public SubAppChanged {
        remoteEntries = remoteEntries == null ? Map.of() : Map.copyOf(remoteEntries);
        sharedRequirements = sharedRequirements == null ? Map.of() : Map.copyOf(sharedRequirements);
    }
}
