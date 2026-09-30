package io.saas.forge.example;

/** 持久化的完成结果保留原始响应文本，重试不重新序列化资源或执行写入。 */
record ProjectWriteResult(int status, String body, String location) {}
