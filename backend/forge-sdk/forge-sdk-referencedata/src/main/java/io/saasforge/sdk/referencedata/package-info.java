/**
 * 参考数据：读取当前租户生效的 Dictionary 与 Parameter（入口 {@link io.saasforge.sdk.referencedata.ReferenceData}），
 * 以 {@link io.saasforge.sdk.referencedata.DictionaryValue} 校验字典取值；按租户缓存，按 iam 在 Redis 发布的版本号失效。
 */
package io.saasforge.sdk.referencedata;
