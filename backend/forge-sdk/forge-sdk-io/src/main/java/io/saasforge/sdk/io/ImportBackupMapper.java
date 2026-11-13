package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** 快照只受租户隔离与 RLS 限制，不按当前成员的 Data Policy 过滤。 */
interface ImportBackupMapper {
    /** 写入覆盖前旧值；同一任务重复覆盖同一记录时保留最初的取值。 */
    void capture(
            @Param("id") UUID id,
            @Param("taskId") UUID taskId,
            @Param("recordId") String recordId,
            @Param("values") String values,
            @Param("expiresAt") OffsetDateTime expiresAt);
    /** 保存该任务最后一次覆盖后的修改标记。 */
    void complete(@Param("taskId") UUID taskId, @Param("recordId") String recordId, @Param("version") String version);
    /** 在业务行锁内标记同模型同记录的其他已完成覆盖快照，与业务写入同一事务；不受 Data Policy 过滤。 */
    void supersede(@Param("taskId") UUID taskId, @Param("recordId") String recordId);
    /** 读取一条最新快照状态，供执行时在业务行锁内重查；只受租户隔离。 */
    ImportBackupState state(@Param("taskId") UUID taskId, @Param("recordId") String recordId);
    /** 业务行锁后锁定快照，避免检查与恢复之间被清理；不受 Data Policy 过滤。 */
    ImportBackupState lockState(@Param("taskId") UUID taskId, @Param("recordId") String recordId);
    /** 分页读取当前租户中属于该任务的未删除快照。 */
    List<ImportBackupData> list(@Param("taskId") UUID taskId, @Param("limit") int limit, @Param("offset") long offset);
    /** 同步任务的快照数量与实际截止时间，与业务写入处于同一事务。 */
    void refreshTask(@Param("taskId") UUID taskId);
    /** 在整次执行事务中锁定本人任务，串行化重复请求；进程终止会自动释放锁，不受 Data Policy 过滤。 */
    UUID lockTask(@Param("taskId") UUID taskId, @Param("member") UUID member);
    /** 在业务行锁内保存逐记录结果；RESTORED 与业务写入同事务，不受 Data Policy 过滤。 */
    int rollbackRecord(@Param("id") UUID id, @Param("status") String status, @Param("actor") UUID actor);
    /** 汇总已提交的逐记录结果并增加完成调用次数；不改变原始导入状态，不受 Data Policy 过滤。 */
    void rollbackTask(@Param("taskId") UUID taskId, @Param("status") String status, @Param("actor") UUID actor);
    /** 到期后清空旧值并逻辑删除快照，保留任务上的总数与截止时间；不受 Data Policy 过滤。 */
    List<UUID> expiredTasks();
    /** 清理前锁住所属任务，与执行汇总互斥；不受 Data Policy 过滤。 */
    UUID lockExpiryTask(@Param("taskId") UUID taskId);
    /** 清理前汇总已提交记录，保留中断执行尚未汇总的历史结果；不受 Data Policy 过滤。 */
    void preserveRollbackResults(@Param("taskId") UUID taskId);
    /** 清空指定过期任务的快照旧值并逻辑删除；不受 Data Policy 过滤。 */
    void expire(@Param("taskId") UUID taskId);
    /** 租户删除时清空旧值并逻辑删除当前租户全部快照。 */
    void markTenantDeleted();
    /** 清理后撤销当前租户任务上的快照可用元数据。 */
    void clearTaskSnapshots();
}
