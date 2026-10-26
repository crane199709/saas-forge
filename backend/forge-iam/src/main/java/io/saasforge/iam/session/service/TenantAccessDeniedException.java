package io.saasforge.iam.session.service;

/** 请求进入的 Membership 不属于当前身份，或其本身、所在租户不是 active。 */
public final class TenantAccessDeniedException extends RuntimeException {}
