package io.saasforge.iam.verification.model;

/** 验证码已受理：有效期与距可再次获取的秒数。未注册的登录目标也返回相同结果，避免据此探测账号。 */
public record CodeDelivery(long expiresIn, long resendIn) {}
