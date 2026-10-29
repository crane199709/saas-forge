package io.saasforge.platform.subapp.model;

/**
 * 关联表中的一行：入口地址为（环境，地址），最低共享依赖版本为（包名，版本）。Mapper 查询投影。
 *
 * @param key 环境名或包名
 * @param value 入口地址或最低版本
 */
public record SubAppRow(String key, String value) {}
