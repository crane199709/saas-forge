package io.saas.forge.sdk.permission;

/** 权威判定明确拒绝，或当前用户凭据不再有效。 */
public final class PermissionDeniedException extends RuntimeException {
    public PermissionDeniedException() {
        super("Permission denied");
    }
}
