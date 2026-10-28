package io.saasforge.iam.role.model;

/** 租户 Owner 的联系方式查询投影；邮箱与手机号至少有一项。 */
public record OwnerContact(String email, String phone) {
    /** 优先使用邮箱。 */
    public String target() {
        return email != null ? email : phone;
    }
}
