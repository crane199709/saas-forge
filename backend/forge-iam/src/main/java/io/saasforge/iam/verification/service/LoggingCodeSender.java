package io.saasforge.iam.verification.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

// 开发用默认实现：验证码与邀请链接明文写入日志，生产环境必须声明 SmsSender、EmailSender Bean 替换。
class LoggingCodeSender implements SmsSender, EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingCodeSender.class);

    private final String channel;

    LoggingCodeSender(String channel) {
        this.channel = channel;
        log.warn("No {} sender configured, messages are written to the log (development only)", channel);
    }

    @Override
    public void send(String target, ContactMessage message) {
        if (message instanceof VerificationMessage verification) {
            log.info(
                    "[dev {}] {} verification code for {}: {} (valid {}s)",
                    channel,
                    verification.purpose(),
                    target,
                    verification.code(),
                    verification.ttl().toSeconds());
        } else if (message instanceof InvitationMessage invitation) {
            log.info(
                    "[dev {}] invitation to tenant {} for {}: {} (valid {}s)",
                    channel,
                    invitation.tenantName(),
                    target,
                    invitation.acceptUrl(),
                    invitation.ttl().toSeconds());
        } else if (message instanceof ExpiryReminderMessage reminder) {
            log.info(
                    "[dev {}] {} of tenant {} expires at {}, reminding {}",
                    channel,
                    reminder.trial() ? "trial" : "subscription",
                    reminder.tenantName(),
                    reminder.expiresAt(),
                    target);
        }
    }
}
