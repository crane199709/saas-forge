package io.saasforge.iam.verification.service;

/**
 * 邮件验证码发送 SPI。部署方声明此类型的 Bean 即替换默认实现；未声明时使用把验证码写入日志的开发实现。
 */
@FunctionalInterface
public interface EmailSender {
    /** 向邮箱发送验证码；抛出异常时本次发送失败，调用方收到 5xx。 */
    void send(String email, VerificationMessage message);
}
