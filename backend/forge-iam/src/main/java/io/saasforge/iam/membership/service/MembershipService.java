package io.saasforge.iam.membership.service;

import io.saasforge.iam.membership.mapper.MembershipMapper;
import io.saasforge.iam.membership.model.Invitation;
import io.saasforge.iam.membership.model.Member;
import io.saasforge.iam.membership.model.MemberContactRow;
import io.saasforge.iam.membership.model.MembershipChange;
import io.saasforge.iam.membership.model.MembershipStatus;
import io.saasforge.sdk.core.UuidV7;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** 租户内 Membership 的资料与 Extended Attribute 值。值的合法性由组织功能包按属性定义校验。 */
@Service
public class MembershipService {
    private static final TypeReference<Map<String, Object>> ATTRIBUTES = new TypeReference<>() {};

    private final MembershipMapper memberships;
    private final JsonMapper json;

    MembershipService(MembershipMapper memberships, JsonMapper json) {
        this.memberships = memberships;
        this.json = json;
    }

    /** 邀请身份加入租户，返回其 Membership；已有 Membership 时原样返回，不改变状态。由开通调用，没有操作人。 */
    public MembershipStatus invite(UUID tenantId, UUID identityId) {
        return invite(tenantId, identityId, null);
    }

    /**
     * 邀请身份加入租户，返回其 Membership：没有时建立 invited，已离开的改回 invited，其余状态原样返回。
     *
     * @param actor 发起邀请的 Identity
     */
    public MembershipStatus invite(UUID tenantId, UUID identityId, UUID actor) {
        memberships.insertInvited(UuidV7.generate(), identityId, tenantId, actor);
        MembershipStatus membership = memberships.findStatus(identityId, tenantId);
        if (MembershipStatus.LEFT.equals(membership.status())) {
            memberships.reinvite(tenantId, membership.membershipId(), actor);
            return new MembershipStatus(membership.membershipId(), MembershipStatus.INVITED);
        }
        return membership;
    }

    /** 租户内 Membership 的状态；不存在时为空。 */
    public Optional<String> status(UUID tenantId, UUID membershipId) {
        return Optional.ofNullable(memberships.status(tenantId, membershipId));
    }

    /**
     * 状态从 {@code from} 改为 {@code to} 并递增版本。调用方须在事务提交后经 {@link MembershipStateService#publish} 发布。
     *
     * @return 新版本；当前状态不是 {@code from} 时为空
     */
    public Optional<MembershipChange> transition(UUID tenantId, UUID membershipId, String from, String to, UUID actor) {
        return Optional.ofNullable(memberships.transition(tenantId, membershipId, from, to, actor));
    }

    /**
     * 改为 left 并清空 Extended Attribute 值、递增版本。调用方须在事务提交后发布。
     *
     * @return 新版本；已离开时为空
     */
    public Optional<MembershipChange> leave(UUID tenantId, UUID membershipId, UUID actor) {
        return Optional.ofNullable(memberships.leave(tenantId, membershipId, actor));
    }

    /** 租户内 active 的 Membership 是否存在；须在事务内调用，该行在事务结束前不会被并发停用或移除。 */
    public boolean lockActive(UUID tenantId, UUID membershipId) {
        return memberships.lockActive(tenantId, membershipId);
    }

    /** 成员管理列表的一页，含已离开的成员；条件为 null 时不过滤。 */
    public List<MemberContactRow> search(UUID tenantId, String keyword, String status, long offset, int limit) {
        return memberships.search(tenantId, keyword, status, offset, limit);
    }

    public long countSearch(UUID tenantId, String keyword, String status) {
        return memberships.countSearch(tenantId, keyword, status);
    }

    /** 邀请令牌指向的 Membership；已删除时为空。 */
    public Optional<Invitation> findInvitation(UUID membershipId) {
        return Optional.ofNullable(memberships.findInvitation(membershipId));
    }

    /** 接受邀请；已接受或不存在时返回 false。 */
    public boolean activateInvited(UUID membershipId) {
        return memberships.activateInvited(membershipId) > 0;
    }

    /** 租户内是否存在未离开的该 Membership；在事务内调用时，该行在事务结束前不会被并发移除。 */
    public boolean exists(UUID tenantId, UUID membershipId) {
        return memberships.exists(tenantId, membershipId);
    }

    /** 一页未离开的成员，按 Membership 标识（UUIDv7，即大致的创建顺序）排序。 */
    public List<Member> page(UUID tenantId, long offset, int limit) {
        return memberships.page(tenantId, offset, limit).stream()
                .map(row -> new Member(
                        row.membershipId(), row.account(), row.displayName(), row.status(), parse(row.attributes())))
                .toList();
    }

    /** Membership 所属的租户；不存在或已删除时为空。 */
    public Optional<UUID> tenantOf(UUID membershipId) {
        return Optional.ofNullable((UUID) memberships.tenantOf(membershipId));
    }

    /** 租户内未离开的成员数。 */
    public long count(UUID tenantId) {
        return memberships.count(tenantId);
    }

    /** Extended Attribute 值；Membership 不存在时为空。 */
    public Map<String, Object> attributes(UUID tenantId, UUID membershipId) {
        String value = memberships.attributes(tenantId, membershipId);
        return value == null ? Map.of() : parse(value);
    }

    /** 整体替换 Extended Attribute 值；Membership 不存在时返回 false，由调用方映射为自己的错误码。 */
    public boolean updateAttributes(UUID tenantId, UUID membershipId, Map<String, Object> attributes, UUID actor) {
        return memberships.updateAttributes(tenantId, membershipId, json.writeValueAsString(attributes), actor) > 0;
    }

    /** 属性定义删除后移除各成员的该属性值，返回被修改的 Membership。 */
    public List<UUID> removeAttribute(UUID tenantId, String code, UUID actor) {
        return memberships.removeAttribute(tenantId, code, actor);
    }

    private Map<String, Object> parse(String value) {
        return json.readValue(value, ATTRIBUTES);
    }
}
