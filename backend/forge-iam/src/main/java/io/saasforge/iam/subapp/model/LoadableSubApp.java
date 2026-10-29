package io.saasforge.iam.subapp.model;

import java.util.Map;

/**
 * Shell App 可加载的一个 Sub-App：当前环境的入口地址与所需的最低共享依赖版本。
 *
 * @param code Sub-App 编码，Shell 将其路由挂在 {@code /apps/<code>} 下
 * @param name 名称
 * @param version 当前登记的版本
 * @param remoteEntry 当前环境的 {@code remoteEntry.js} 地址
 * @param sharedRequirements 所需的最低共享依赖版本，键为包名
 */
public record LoadableSubApp(
        String code, String name, String version, String remoteEntry, Map<String, String> sharedRequirements) {}
