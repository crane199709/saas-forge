package io.saasforge.sdk.storage;

/**
 * 当前租户在本服务中的存储用量。
 *
 * @param usedBytes 全部未物理删除的对象登记大小（字节），包含未确认上传和失效文件
 * @param quotaBytes Plan 的存储容量上限（字节），为 null 表示不限
 */
public record StorageUsage(long usedBytes, Long quotaBytes) {}
