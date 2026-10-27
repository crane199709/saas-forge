package io.saasforge.iam.verification.service;

/**
 * 短信验证码发送 SPI。部署方声明此类型的 Bean 即替换默认实现；未声明时使用把验证码写入日志的开发实现。
 */
@FunctionalInterface
public interface SmsSender {
    /** 向手机号发送验证码；抛出异常时本次发送失败，调用方收到 5xx。 */
    void send(String phone, VerificationMessage message);
}
