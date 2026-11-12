package io.saasforge.demo.masking.model;

import io.saasforge.sdk.io.ExcelColumn;
import io.saasforge.sdk.security.SensitiveField;

/** 同一个声明供在线与导出复用，防止同一路径在多个 DTO 中重复登记。 */
public record MaskedProfile(
        @ExcelColumn(value = "手机号", order = 0)
        @SensitiveField(path = "profile.phone", grade = "L3", defaultRule = "phone")
        String phone,

        @ExcelColumn(value = "身份证", order = 1)
        @SensitiveField(path = "profile.idCard", grade = "L3", defaultRule = "idcard")
        String idCard,

        @ExcelColumn(value = "说明", order = 2) String note) {
    @Override
    public String toString() {
        return "MaskedProfile[sensitive fields omitted]";
    }
}
