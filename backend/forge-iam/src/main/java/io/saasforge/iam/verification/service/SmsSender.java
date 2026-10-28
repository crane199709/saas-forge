package io.saasforge.iam.verification.service;

/**
 * 短信发送 SPI，发送验证码与邀请等 {@link ContactMessage}。部署方声明此类型的 Bean 即替换默认实现；未声明时使用把消息写入日志的开发实现。
 */
@FunctionalInterface
public interface SmsSender {
    /** 向手机号发送消息，按消息类型选择模板；抛出异常时本次发送失败。 */
    void send(String phone, ContactMessage message);
}
