package io.saasforge.iam.security.config;

/** 同进程 Token 交换适配器持有的客户端密钥，tenant-app 与 platform-console 两个注册客户端共用。 */
public record ClientCredentials(String secret) {
    @Override
    public String toString() {
        return "ClientCredentials[redacted]";
    }
}
