package io.saasforge.sdk.storage;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 业务文件引用检查扩展点；实现注册为 Spring Bean，由存储治理在当前租户下批量调用。
 *
 * <p>用途覆盖表示检查该用途的全部业务引用，必须包含逻辑删除行，且不能按当前成员的数据权限缩窄。
 * 未覆盖的用途不会自动清理；附件检查器不能宣称覆盖 IO 等其他用途。
 */
public interface FileReferenceChecker {
    /** 返回本实现完整检查的稳定、非 null 用途编码集合。 */
    Set<String> purposes();

    /**
     * 返回候选中仍被业务引用的对象 ID。调用不携带请求身份或调用方事务，只携带当前租户。
     *
     * @param purpose 已由 {@link #purposes()} 声明覆盖的用途
     * @param candidates 不可变候选列表，不得逐对象查询；大小不超过配置的批量上限
     * @return 候选集合的非 null 子集；空集表示已确认全部无引用
     */
    Set<UUID> referencedObjects(String purpose, List<UUID> candidates);
}
