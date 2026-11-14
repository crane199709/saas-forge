package io.saasforge.platform.plan.entity;

import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import io.saasforge.sdk.tenant.AuditableEntity;
import java.util.UUID;
import org.apache.ibatis.type.ArrayTypeHandler;

/** 套餐：Feature Pack 与 Quota。数组列经 MyBatis 的 {@link ArrayTypeHandler} 映射为 PostgreSQL {@code text[]}。 */
@TableName(value = "plan", autoResultMap = true)
public class Plan extends AuditableEntity {
    private UUID id;
    private String code;
    private String name;

    @TableField(typeHandler = ArrayTypeHandler.class)
    private String[] permissions;

    @TableField(typeHandler = ArrayTypeHandler.class)
    private String[] subApps;

    // 默认更新策略跳过 null 字段；改为不限时须把原上限清空
    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Integer memberQuota;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long storageQuotaMb;

    @TableField(updateStrategy = FieldStrategy.ALWAYS)
    private Long tokenQuota;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String[] getPermissions() {
        return permissions;
    }

    public void setPermissions(String[] permissions) {
        this.permissions = permissions;
    }

    public String[] getSubApps() {
        return subApps;
    }

    public void setSubApps(String[] subApps) {
        this.subApps = subApps;
    }

    public Integer getMemberQuota() {
        return memberQuota;
    }

    public void setMemberQuota(Integer memberQuota) {
        this.memberQuota = memberQuota;
    }

    public Long getStorageQuotaMb() {
        return storageQuotaMb;
    }

    public void setStorageQuotaMb(Long storageQuotaMb) {
        this.storageQuotaMb = storageQuotaMb;
    }

    public Long getTokenQuota() {
        return tokenQuota;
    }

    public void setTokenQuota(Long tokenQuota) {
        this.tokenQuota = tokenQuota;
    }
}
