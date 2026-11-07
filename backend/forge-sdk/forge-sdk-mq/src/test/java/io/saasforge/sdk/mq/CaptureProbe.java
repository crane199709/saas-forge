package io.saasforge.sdk.mq;

/** 集成测试的持久化边界探针，业务写入与消费事务共同提交或回滚。 */
interface CaptureProbe {
    /** 记录一次业务写入。 */
    void writeBusiness();
    /** 返回已提交业务写入数。 */
    long businessWrites();
    /** 返回已持久化的死信数。 */
    long deadEvents();
    /** 返回捕获时的原始消息体。 */
    Payload deadPayload();
    /** 返回捕获时的原始租户头。 */
    String originalTenantId();

    /** 将未发事件推迟到明天。 */
    void deferOutbox();
    /** 标记所有事件已发送。 */
    void completeOutbox();

    record Payload(byte[] payload) {}
}
