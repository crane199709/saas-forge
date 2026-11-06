package io.saasforge.iam.mfa.model;

/**
 * 开始绑定 Authenticator 的响应，Identity、Operator 与 Operator 登录中当场绑定共用。
 *
 * @param otpauthUri 供验证器 App 扫码的 URI，含未加密的密钥
 */
public record BindingStartResponse(String otpauthUri) {
    @Override
    public String toString() {
        return "BindingStartResponse[redacted]";
    }
}
