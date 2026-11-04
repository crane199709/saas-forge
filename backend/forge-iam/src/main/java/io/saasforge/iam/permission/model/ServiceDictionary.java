package io.saasforge.iam.permission.model;

import io.saasforge.sdk.core.ResourceManifest.Dictionary;

/**
 * 某服务清单中声明的 Dictionary。
 *
 * @param service 声明该字典的服务
 * @param dictionary 字典及其内置项
 */
public record ServiceDictionary(String service, Dictionary dictionary) {}
