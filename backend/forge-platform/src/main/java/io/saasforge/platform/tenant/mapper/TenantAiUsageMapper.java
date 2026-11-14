package io.saasforge.platform.tenant.mapper;

import io.saasforge.platform.tenant.model.*;
import io.saasforge.platform.tenant.service.AiUsageDailyReported;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** 全局运营投影不按 CurrentTenant 过滤；HTTP 必须先通过 TenantService 的 tenant Data Policy。 */
@Mapper
public interface TenantAiUsageMapper {
    /** 最终删除租户的投影逻辑删除；全局表显式限定租户，不受成员 Data Policy 过滤。 */
    void clean(@Param("tenantId") UUID tenantId);

    /** 按租户、日期仅替换更新版本，旧/重复快照无变化。 */
    void apply(@Param("id") UUID id, @Param("event") AiUsageDailyReported event);
    /** 固定已校验租户内读取月汇总。 */
    TenantAiUsageTotals totals(
            @Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);
    /** 固定已校验租户内读取每日快照。 */
    List<TenantAiUsageDay> days(
            @Param("tenantId") UUID tenantId, @Param("from") LocalDate from, @Param("to") LocalDate to);
}
