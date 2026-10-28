package io.saasforge.iam.verification.service;

/**
 * 经 {@link EmailSender}、{@link SmsSender} 发往邮箱或手机的消息（验证码、邀请、租户到期提醒）。发送实现按具体类型选择模板；类型是封闭的，发送实现可以穷举。
 */
public sealed interface ContactMessage permits VerificationMessage, InvitationMessage, ExpiryReminderMessage {}
