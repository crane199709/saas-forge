package io.saasforge.sdk.numbering;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.util.UUID;
import org.apache.ibatis.annotations.Param;

/**
 * 计数表 {@code forge_business_number} 的读写，SQL 在同目录的 {@code BusinessNumberMapper.xml}。除 {@link #insertIfAbsent} 外都由租户拦截注入
 * 当前租户条件，均不受 Data Policy 过滤。
 */
interface BusinessNumberMapper {
    /**
     * 计数行不存在时以计数 0 创建；已存在（含其他事务尚未提交的插入）时什么也不做，并发首次取号因此不会违反唯一索引。
     *
     * <p>{@code ON CONFLICT ... WHERE} 交给租户拦截改写不可靠，因此不经拦截，租户由参数显式写入；RLS 仍限定只能写当前租户。
     * 不经拦截也就不经写入保护，随后的 {@link #increment} 会经过。
     *
     * @param tenantId 当前租户
     * @param id 新计数行的 ID
     * @param rule 规则编码
     * @param period 计数周期，无日期段时为空串
     * @param actor 当前操作人，没有认证上下文时为 null
     */
    @InterceptorIgnore(tenantLine = "true")
    void insertIfAbsent(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id,
            @Param("rule") String rule,
            @Param("period") String period,
            @Param("actor") UUID actor);

    /** 计数加一并锁定计数行，直到事务结束；同一行上的并发取号在此排队。 */
    void increment(@Param("rule") String rule, @Param("period") String period, @Param("actor") UUID actor);

    /**
     * 读取本事务刚递增后的计数。
     *
     * @return 当前计数
     */
    long current(@Param("rule") String rule, @Param("period") String period);

    /** 租户删除时把当前租户的计数行全部标记删除，没有操作人。 */
    void markTenantDeleted();
}
