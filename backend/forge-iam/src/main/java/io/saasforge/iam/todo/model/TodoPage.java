package io.saasforge.iam.todo.model;

import java.util.List;

/** 同次查询的待办列表与分页总数。 */
public record TodoPage(List<TodoResponse> items, long total) {}
