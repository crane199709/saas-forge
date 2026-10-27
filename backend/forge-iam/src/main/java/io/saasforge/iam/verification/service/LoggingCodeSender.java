package io.saasforge.iam.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 开发用默认实现：验证码明文写入日志，生产环境必须声明 SmsSender、EmailSender Bean 替换。
class LoggingCodeSender implements SmsSender, EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingCodeSender.class);

    private final String channel;

    LoggingCodeSender(String channel) {
        this.channel = channel;
        log.warn("No {} sender configured, verification codes are written to the log (development only)", channel);
    }

    @Override
    public void send(String target, VerificationMessage message) {
        log.info(
                "[dev {}] {} verification code for {}: {} (valid {}s)",
                channel,
                message.purpose(),
                target,
                message.code(),
                message.ttl().toSeconds());
    }
}
