package io.saasforge.sdk.storage;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 对象登记与存储容量 Quota 的读写，SQL 在同目录的 {@code StorageObjectMapper.xml}。除 {@link #applyQuota} 外都由租户拦截注入当前租户条件，
 * 均不受 Data Policy 过滤。
 */
interface StorageObjectMapper {
    /**
     * 登记一个待上传的对象，租户由租户拦截写入。
     *
     * @param purpose 已校验的用途编码，后续用途检查以登记值为准
     * @param uploadExpiresAt 上传 URL 的失效时间，此后未确认的对象不再占用容量
     */
    void insertPending(
            @Param("id") UUID id,
            @Param("objectKey") String objectKey,
            @Param("purpose") String purpose,
            @Param("filename") String filename,
            @Param("contentType") String contentType,
            @Param("size") long size,
            @Param("uploadExpiresAt") OffsetDateTime uploadExpiresAt);

    /**
     * 按 ID 读取当前租户的对象登记。
     *
     * @return 不存在或属于其他租户时为 null
     */
    StorageObjectRecord findById(@Param("id") UUID id);

    /**
     * 当前租户的已用容量：已确认的对象与在 {@code now} 时尚未过期的待上传对象的总大小。
     *
     * @param excludeId 不计入的对象，确认上传时排除对象自身；为 null 时全部计入
     */
    long usedBytes(@Param("now") OffsetDateTime now, @Param("excludeId") UUID excludeId);

    /**
     * 读取当前租户的存储容量上限。
     *
     * @return 字节数；未登记或不限时为 null
     */
    Long quota();

    /**
     * 读取并锁定当前租户的存储容量上限，直到事务结束；同一租户的并发上传因此依次比较已用容量。未登记的租户不锁定，它不受限。
     *
     * @return 字节数；未登记或不限时为 null
     */
    Long lockQuota();

    /**
     * 把待上传对象标记为已确认。
     *
     * @return 标记成功为 1，对象不存在或已确认时为 0
     */
    int markStored(@Param("id") UUID id);

    /** 删除当前租户的对象登记：业务删除对象，或确认上传时因超出容量而拒绝。 */
    void delete(@Param("id") UUID id);

    /**
     * 应用 Plan 变更中的存储容量：未登记时登记，已登记且已应用的版本更旧时更新。
     *
     * <p>{@code ON CONFLICT ... WHERE} 交给租户拦截改写不可靠，因此不经拦截，租户由参数显式写入；RLS 仍限定只能写当前租户。
     *
     * @param quotaBytes 存储容量上限（字节），null 表示不限
     * @return 应用了变更为 1；已应用同一或更新的版本时为 0
     */
    @InterceptorIgnore(tenantLine = "true")
    int applyQuota(
            @Param("tenantId") UUID tenantId, @Param("quotaBytes") Long quotaBytes, @Param("version") long version);
}
