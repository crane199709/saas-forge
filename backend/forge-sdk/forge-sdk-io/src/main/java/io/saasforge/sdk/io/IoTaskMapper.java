package io.saasforge.sdk.io;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/** 任务基础设施不受 Data Policy 过滤；每条 SQL 仍经租户拦截与 RLS。 */
interface IoTaskMapper {
    /** 登记等待任务，写入标准列；不受 Data Policy 过滤。 */
    void insert(
            @Param("id") UUID id,
            @Param("type") String type,
            @Param("member") UUID member,
            @Param("actor") UUID actor,
            @Param("source") UUID source,
            @Param("summary") String summary);
    /** 查询本人任务；不受 Data Policy 过滤。 */
    IoTask own(@Param("id") UUID id, @Param("member") UUID member);
    /** 分页查询本人任务；不受 Data Policy 过滤。 */
    List<IoTask> list(@Param("member") UUID member, @Param("limit") int limit, @Param("offset") long offset);
    /** 统计本人任务；不受 Data Policy 过滤。 */
    long count(@Param("member") UUID member);
    /** 开始任务，只有等待状态可进入执行中；不受 Data Policy 过滤。 */
    int start(@Param("id") UUID id, @Param("actor") UUID actor);
    /** 刷新仍在运行的任务心跳；不受 Data Policy 过滤。 */
    int heartbeat(@Param("id") UUID id, @Param("rows") long rows);
    /** 完成仍在执行的任务，条件更新防止超时收尾被覆盖；不受 Data Policy 过滤。 */
    int finish(
            @Param("id") UUID id,
            @Param("status") String status,
            @Param("rows") long rows,
            @Param("code") String code,
            @Param("reason") String reason,
            @Param("result") UUID result,
            @Param("actor") UUID actor);
    /** 查出需要中断收尾的任务，供清理源文件；不受 Data Policy 过滤。 */
    List<IoRecoveryTask> staleTasks(@Param("cutoff") OffsetDateTime cutoff);
    /** 收尾本租户超时任务；不受 Data Policy 过滤。 */
    int interrupt(@Param("cutoff") OffsetDateTime cutoff);
    /** 取一批过期结果；不受 Data Policy 过滤。 */
    List<IoTask> expired(@Param("cutoff") OffsetDateTime cutoff);
    /** 删除结果登记引用，保留任务；不受 Data Policy 过滤。 */
    void clearResult(@Param("id") UUID id);
}
