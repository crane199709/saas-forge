/**
 * S3 协议对象存储抽象（ADR-0011）：对象 key 由 SDK 生成并强制带 {@code {tenant_id}/{service}/} 前缀，访问一律经预签名 URL，
 * 上传前按租户的存储容量 Quota 校验。入口为 {@link io.saasforge.sdk.storage.ObjectStorage}。
 */
package io.saasforge.sdk.storage;
