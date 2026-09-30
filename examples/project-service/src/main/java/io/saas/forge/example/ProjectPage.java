package io.saas.forge.example;

import java.util.List;

record ProjectPage(List<ProjectResult> items, String nextCursor, boolean hasMore) {}
