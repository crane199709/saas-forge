package io.saasforge.sdk.mq;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Param;

/**
 * 消费去重记录，SQL 在同目录的 {@code ConsumedMessageMapper.xml}。
 *
 * <p>去重按消费组与消息 ID 判断，与租户无关，因此不注入租户条件，表上也不启用 RLS。
 */
@InterceptorIgnore(tenantLine = "true")
interface ConsumedMessageMapper {
    /**
     * 登记一条消息已被本消费组处理。与处理逻辑在同一事务中执行：处理失败回滚后登记随之撤销，重投时仍会处理。
     * 不受 Data Policy 过滤。
     *
     * @param consumerGroup 消费组
     * @param messageId 消息 ID
     * @return 首次登记为 1；已登记过（重复投递）为 0
     */
    int insertIfAbsent(@Param("consumerGroup") String consumerGroup, @Param("messageId") String messageId);
}
