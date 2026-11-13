package io.saasforge.iam.datasecurity.model;

public record GradeResponse(String grade, String name, String defaultRule, boolean exportAllowed) {}
