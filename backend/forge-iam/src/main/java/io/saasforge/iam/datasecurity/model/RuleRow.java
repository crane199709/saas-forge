package io.saasforge.iam.datasecurity.model;

import io.saasforge.sdk.security.MaskingRule;

/** 数据库查询投影；不作为 HTTP 响应。 */
public record RuleRow(String code, String name, String strategy, Integer keepFirst, String replacement) {
    public MaskingRule rule() {
        return new MaskingRule(strategy, keepFirst, replacement);
    }

    public RuleResponse response() {
        return new RuleResponse(code, name, rule());
    }
}
