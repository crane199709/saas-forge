package io.saas.forge.example;

import java.util.List;

public record TaskPage(List<TaskResult> items, String nextCursor, boolean hasMore) {}
