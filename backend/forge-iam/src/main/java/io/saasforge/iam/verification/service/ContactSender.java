package io.saasforge.iam.verification.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/** 按目标是邮箱还是手机号选择 {@link EmailSender} 或 {@link SmsSender} 发送。 */
@Service
public class ContactSender {
    private final SmsSender sms;
    private final EmailSender email;

    ContactSender(ObjectProvider<SmsSender> sms, ObjectProvider<EmailSender> email) {
        // 用 ObjectProvider 而不是条件 Bean：普通配置类中的 @ConditionalOnMissingBean 受注册顺序影响，可能与部署方的实现并存。
        this.sms = sms.getIfUnique(() -> new LoggingCodeSender("sms"));
        this.email = email.getIfUnique(() -> new LoggingCodeSender("email"));
    }

    /** 发送失败时抛出发送实现的异常。 */
    public void send(String target, ContactMessage message) {
        if (VerificationCodeService.isEmail(target)) email.send(target, message);
        else sms.send(target, message);
    }
}
