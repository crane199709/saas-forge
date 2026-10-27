package io.saasforge.iam.operator.model;

import java.util.UUID;

public record ActiveOperator(UUID operatorId, String account, String displayName) {}
