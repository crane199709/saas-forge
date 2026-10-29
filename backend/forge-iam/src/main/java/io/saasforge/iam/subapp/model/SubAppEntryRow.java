package io.saasforge.iam.subapp.model;

/**
 * 已登记某环境入口地址的 Sub-App，Mapper 查询投影。
 *
 * @param code Sub-App 编码
 * @param name 名称
 * @param version 当前登记的版本
 * @param remoteEntry 该环境的入口地址
 */
public record SubAppEntryRow(String code, String name, String version, String remoteEntry) {}
