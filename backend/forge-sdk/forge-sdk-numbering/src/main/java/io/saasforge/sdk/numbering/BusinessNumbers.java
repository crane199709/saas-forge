package io.saasforge.sdk.numbering;

import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.core.UuidV7;
import java.time.Clock;
import java.time.ZoneId;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 取号入口：按 {@link NumberingRule} 为当前租户生成下一个 Business Number。
 *
 * <p>取号对「租户 + 规则 + 计数周期」的计数行加锁递增，必须在调用方保存业务数据的同一事务中进行：事务回滚时号一起回滚，编号因此连续不跳号；
 * 同一规则的并发取号在这一行上排队，直到持锁事务结束。为缩短排队时间，在事务中尽量靠后取号。取号不记审计。
 */
public final class BusinessNumbers {
    private final BusinessNumberMapper mapper;
    private final ZoneId businessZone;
    private final Clock clock;
    private final Supplier<UUID> currentActor;

    BusinessNumbers(BusinessNumberMapper mapper, ZoneId businessZone, Clock clock, Supplier<UUID> currentActor) {
        this.mapper = mapper;
        this.businessZone = businessZone;
        this.clock = clock;
        this.currentActor = currentActor;
    }

    /**
     * 生成下一个编号，如 {@code SO-20261104-0007}。日期段取当前时刻在 Business Time Zone 中的日期，跨入新的计数周期时序号从 1 开始。
     *
     * @param rule 编号规则
     * @return Business Number
     * @throws IllegalStateException 不在事务中，或没有租户上下文
     */
    public String next(NumberingRule rule) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 事务外取号会立即提交计数，业务数据随后失败时就会留下空号
            throw new IllegalStateException("Business number for rule " + rule.code()
                    + " must be taken inside the transaction that saves the business data");
        }
        UUID tenantId = CurrentTenant.require();
        String period = rule.dateSegment() == null
                ? NumberingRule.NO_PERIOD
                : rule.dateSegment().format(clock.instant(), businessZone);
        UUID actor = currentActor.get();
        mapper.insertIfAbsent(tenantId, UuidV7.generate(), rule.code(), period, actor);
        mapper.increment(rule.code(), period, actor);
        return rule.format(period, mapper.current(rule.code(), period));
    }
}
