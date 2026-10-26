package io.saasforge.demo.order.model;

/** 进入 Mapper XML 的排序条件：列名取自服务端白名单，方向只能是 ASC 或 DESC。 */
public record OrderSort(String column, String direction) {}
