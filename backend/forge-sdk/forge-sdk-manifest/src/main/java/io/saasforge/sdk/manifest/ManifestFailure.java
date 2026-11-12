package io.saasforge.sdk.manifest;
/** 上报失败的公开分类，不包含服务地址、响应原文或凭证。 */
public enum ManifestFailure {
    NETWORK_UNREACHABLE,
    CREDENTIAL_REJECTED,
    SERVICE_MISMATCH,
    VERSION_CONFLICT,
    INVALID_MANIFEST,
    HTTP_REJECTED,
    UNEXPECTED_RESPONSE
}
