package io.saasforge.iam.membership.model;

import java.util.UUID;

public record MembershipStatus(UUID membershipId, String status) {
    /** 已邀请、尚未接受。 */
    public static final String INVITED = "invited";

    public boolean invited() {
        return INVITED.equals(status);
    }
}
