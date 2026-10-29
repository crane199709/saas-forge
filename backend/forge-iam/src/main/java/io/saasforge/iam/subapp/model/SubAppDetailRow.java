package io.saasforge.iam.subapp.model;

/**
 * 关联表中的一行，Mapper 查询投影：入口地址为（环境，地址），最低共享依赖版本为（包名，版本）。
 *
 * @param key 环境名或包名
 * @param value 入口地址或最低版本
 */
public record SubAppDetailRow(String key, String value) {}
