package io.saasforge.sdk.notification;

import java.util.List;
import java.util.UUID;

/**
 * Notification 或 Todo 的目标：Membership 列表与 Permission Code 二选一。
 * @param membershipIds 接收者，Permission Code 目标时为空列表
 * @param permissionCode 权限码，Membership 目标时为 null
 */
public record NotificationTarget(List<UUID> membershipIds, String permissionCode) {
    /** 固定不可变的目标快照，并保证只选择一种接收方式。 */
    public NotificationTarget {
        membershipIds = membershipIds == null ? List.of() : List.copyOf(membershipIds);
        if (membershipIds.isEmpty() == (permissionCode == null)) {
            throw new IllegalArgumentException("target must select exactly one of membershipIds or permissionCode");
        }
        if (permissionCode != null) NotificationValidation.text(permissionCode, "permissionCode", 255);
    }

    /**
     * 指定 Membership；IAM 忽略不属于当前 Tenant 的 Membership。
     * @param membershipIds 接收者
     * @return Membership 目标
     */
    public static NotificationTarget memberships(List<UUID> membershipIds) {
        return new NotificationTarget(membershipIds, null);
    }

    /**
     * 指定 Permission Code：Notification 创建时展开，Todo 读取时判断。
     * @param permissionCode 权限码
     * @return 权限目标
     */
    public static NotificationTarget permission(String permissionCode) {
        return new NotificationTarget(List.of(), permissionCode);
    }
}
