package io.saasforge.sdk.io;

/** 一条不可自动恢复的记录，仅返回记录主键和原因，不返回覆盖前或当前字段值。 */
public record ImportRollbackConflict(String recordId, ImportRollbackReason reason) {}
