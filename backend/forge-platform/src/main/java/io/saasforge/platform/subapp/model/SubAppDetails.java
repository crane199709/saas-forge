package io.saasforge.platform.subapp.model;

import io.saasforge.platform.subapp.entity.SubApp;
import java.util.SortedMap;

/**
 * 一个 Sub-App 的完整登记内容。
 *
 * @param app 登记行
 * @param remoteEntries 各环境的入口地址，按环境名排序
 * @param sharedRequirements 所需的最低共享依赖版本，按包名排序
 */
public record SubAppDetails(
        SubApp app, SortedMap<String, String> remoteEntries, SortedMap<String, String> sharedRequirements) {}
