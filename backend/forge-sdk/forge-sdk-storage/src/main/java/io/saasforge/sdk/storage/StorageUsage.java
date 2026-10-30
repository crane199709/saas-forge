package io.saasforge.sdk.storage;

/**
 * 当前租户在本服务中的存储用量。
 *
 * @param usedBytes 已确认上传的对象与尚未过期的上传申请的总大小（字节）
 * @param quotaBytes Plan 的存储容量上限（字节），为 null 表示不限
 */
public record StorageUsage(long usedBytes, Long quotaBytes) {}
