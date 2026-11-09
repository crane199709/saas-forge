package io.saasforge.platform.tenant.model;

import java.util.List;

/**
 * 更换租户 Plan 的影响预览；没有任何损失（如升级）时各项为空。
 *
 * @param lostPermissions 将失去的 Permission Code，按编码排序
 * @param lostSubApps 将失去的 Sub-App 编码，按编码排序
 * @param memberCount 当前占用成员数配额的 Membership 数量；既无损失、配额也未降低时无需读取，为 null
 * @param memberQuota 新 Plan 的成员数上限，null 表示不限
 * @param membersOverQuota 成员数超出新上限的差额，未超出时为 0
 * @param affectedMemberships 状态正常、会因本次更换失去能力的 Membership 数量
 */
public record PlanChangeImpactResponse(
        List<String> lostPermissions,
        List<String> lostSubApps,
        Long memberCount,
        Integer memberQuota,
        long membersOverQuota,
        long affectedMemberships) {}
