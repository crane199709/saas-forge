package io.saasforge.sdk.tenant;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * 标准表实体的基类：继承后，创建/修改时间、操作人由 SDK 在经 MyBatis-Plus 写入时填充，删除为逻辑删除，业务代码不需要赋值。
 *
 * <p>调用方设置的审计字段一律被覆盖：INSERT 写入 {@code created_*} 与 {@code updated_*}，UPDATE 只写 {@code updated_*}，
 * {@code created_*} 不进入 UPDATE 语句。mapper XML 中手写的 SQL 不经过填充，须自行维护这些列并带 {@code NOT deleted}。
 * {@code tenant_id} 仍不在实体中声明，由租户拦截器注入。
 */
public abstract class AuditableEntity {
    // 不进入 UPDATE：先查后改的实体带着读出的值，即使被改动也不能写回创建信息
    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;

    @TableField(fill = FieldFill.INSERT, updateStrategy = FieldStrategy.NEVER)
    private UUID createdBy;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private UUID updatedBy;

    @TableLogic
    private Boolean deleted;

    /**
     * 创建时间。
     *
     * @return 行创建时间，由 SDK 在 INSERT 时写入
     */
    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    /**
     * 设置创建时间；经 MyBatis-Plus 写入时会被 SDK 覆盖。
     *
     * @param createdAt 创建时间
     */
    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    /**
     * 最后修改时间，删除时也会刷新。
     *
     * @return 最后一次经 MyBatis-Plus 写入的时间
     */
    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * 设置修改时间；经 MyBatis-Plus 写入时会被 SDK 覆盖。
     *
     * @param updatedAt 修改时间
     */
    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    /**
     * 创建人。
     *
     * @return 创建时已认证 JWT 的 {@code sub}；没有认证上下文（系统写入）时为 null
     */
    public UUID getCreatedBy() {
        return createdBy;
    }

    /**
     * 设置创建人；经 MyBatis-Plus 写入时会被 SDK 覆盖。
     *
     * @param createdBy 创建人
     */
    public void setCreatedBy(UUID createdBy) {
        this.createdBy = createdBy;
    }

    /**
     * 最后修改人，删除时也会刷新。
     *
     * @return 最后一次写入时已认证 JWT 的 {@code sub}；系统写入时为 null
     */
    public UUID getUpdatedBy() {
        return updatedBy;
    }

    /**
     * 设置修改人；经 MyBatis-Plus 写入时会被 SDK 覆盖。
     *
     * @param updatedBy 修改人
     */
    public void setUpdatedBy(UUID updatedBy) {
        this.updatedBy = updatedBy;
    }

    /**
     * 逻辑删除标记。常规查询只返回未删除的行，因此读出的实体通常为 {@code false}。
     *
     * @return 是否已逻辑删除
     */
    public Boolean getDeleted() {
        return deleted;
    }

    /**
     * 设置逻辑删除标记；删除请用 {@code deleteById} 等会触发更新填充的方法，而不是直接修改本字段。
     *
     * @param deleted 是否已逻辑删除
     */
    public void setDeleted(Boolean deleted) {
        this.deleted = deleted;
    }
}
