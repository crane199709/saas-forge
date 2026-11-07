package io.saasforge.sdk.mq;

/** 异常消息可能包含原始 payload 值；运维元数据只保留静态异常类型，完整异常留在服务日志。 */
final class DeadEventErrorSummary {
    private DeadEventErrorSummary() {}

    static String of(RuntimeException failure) {
        return failure.getClass().getName();
    }
}
