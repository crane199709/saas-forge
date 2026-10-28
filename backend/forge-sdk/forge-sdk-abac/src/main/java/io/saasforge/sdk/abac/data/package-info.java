/**
 * 业务服务的数据权限（ADR-0006）：按清单声明的 Data Resource，把当前成员的 Data Policy 注入 SQL，并提供单对象校验
 * {@link io.saasforge.sdk.abac.data.DataPermission}。没有任何策略的资源默认拒绝访问。
 */
package io.saasforge.sdk.abac.data;
