package io.saasforge.iam.datasecurity.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import io.saasforge.iam.datasecurity.model.FieldOverrideRow;
import io.saasforge.iam.datasecurity.model.GradeResponse;
import io.saasforge.iam.datasecurity.model.RuleRow;
import io.saasforge.sdk.security.MaskingRule;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** IAM 显式限定租户条件，不受 Data Policy 过滤。 */
@Mapper
@InterceptorIgnore(tenantLine = "true")
public interface DataSecurityMapper {
    /** 所有规则引用变更与删除共用此事务锁，避免先检查后引用的竞态。 */
    int lock();
    /** 当前平台可选规则。 */
    List<RuleRow> rules();
    /** 当前规则，不存在或已删除返回 null。 */
    RuleRow rule(@Param("code") String code);
    /** 新增固定策略规则，写入完整标准列。 */
    int createRule(
            @Param("id") UUID id,
            @Param("code") String code,
            @Param("name") String name,
            @Param("rule") MaskingRule rule,
            @Param("actor") UUID actor);
    /** 修改规则参数。 */
    int updateRule(
            @Param("code") String code,
            @Param("name") String name,
            @Param("rule") MaskingRule rule,
            @Param("actor") UUID actor);
    /** 逻辑删除无引用规则。 */
    int deleteRule(@Param("code") String code, @Param("actor") UUID actor);
    /** 按等级列出平台固定四级分级。 */
    List<GradeResponse> grades();
    /** 修改固定分级的默认规则。 */
    int updateGrade(@Param("grade") String grade, @Param("code") String code, @Param("actor") UUID actor);
    /** 当前租户的未删除覆盖，字段下线后的覆盖也保留。 */
    List<FieldOverrideRow> overrides(@Param("tenantId") UUID tenantId);
    /** 保存覆盖，与平台删除规则共用事务锁。 */
    int saveOverride(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId,
            @Param("service") String service,
            @Param("path") String path,
            @Param("code") String code,
            @Param("actor") UUID actor);
    /** 恢复字段默认规则。 */
    int resetOverride(
            @Param("tenantId") UUID tenantId,
            @Param("service") String service,
            @Param("path") String path,
            @Param("actor") UUID actor);
    /** 删除租户时逻辑删除覆盖。 */
    int deleteTenant(@Param("tenantId") UUID tenantId);
}
