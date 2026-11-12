package io.saasforge.demo.masking.model;

/** 专用原文操作使用独立 DTO，不能给普通响应增加权限豁免。 */
public record RawProfile(String phone, String idCard) {
    @Override
    public String toString() {
        return "RawProfile[sensitive fields omitted]";
    }
}
