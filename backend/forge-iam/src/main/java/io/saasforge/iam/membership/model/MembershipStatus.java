package io.saasforge.iam.membership.model;

import java.util.UUID;

public record MembershipStatus(UUID membershipId, String status) {
    /** 已邀请、尚未接受。 */
    public static final String INVITED = "invited";

    /** 正常，可以进入租户。 */
    public static final String ACTIVE = "active";

    /** 已停用，可恢复。 */
    public static final String SUSPENDED = "suspended";

    /** 已被移除或主动退出；行保留以便成员列表展示，再次邀请时回到 invited。 */
    public static final String LEFT = "left";

    public boolean invited() {
        return INVITED.equals(status);
    }
}
