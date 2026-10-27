package io.saasforge.iam.membership.model;

import java.util.UUID;

// 成员列表的查询投影；attributes 为 JSON 对象文本，由服务解析后再交给调用方。
public record MemberRow(UUID membershipId, String account, String displayName, String status, String attributes) {}
