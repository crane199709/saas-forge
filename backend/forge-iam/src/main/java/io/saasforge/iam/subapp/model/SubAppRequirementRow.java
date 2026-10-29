package io.saasforge.iam.subapp.model;

/**
 * 某 Sub-App 所需的一条最低共享依赖版本，Mapper 查询投影。
 *
 * @param code Sub-App 编码
 * @param dependency 包名
 * @param minVersion 最低版本 {@code x.y.z}
 */
public record SubAppRequirementRow(String code, String dependency, String minVersion) {}
