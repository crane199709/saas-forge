package io.saasforge.sdk.core;

/**
 * 网关转发给下游服务的客户端 IP 请求头：网关覆盖客户端自带的值，下游可以信任。
 *
 * <p>不复用 {@code X-Forwarded-For}：网关只保留其中属于可信代理的地址，真实客户端地址不在其中；服务的
 * {@code server.forward-headers-strategy: framework} 还会消费该头。
 */
public final class ClientIpHeader {
    /** 请求头名称。 */
    public static final String NAME = "X-Forge-Client-Ip";

    private ClientIpHeader() {}
}
