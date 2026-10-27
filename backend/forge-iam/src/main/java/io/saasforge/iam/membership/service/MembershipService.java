package io.saasforge.iam.membership.service;

import io.saasforge.iam.membership.mapper.MembershipMapper;
import io.saasforge.iam.membership.model.Member;
import java.util.List;
import java.util.Map;
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

    /** 租户内是否存在未删除的该 Membership。 */
    public boolean exists(UUID tenantId, UUID membershipId) {
        return memberships.exists(tenantId, membershipId);
    }

    /** 一页成员，按 Membership 标识（UUIDv7，即大致的创建顺序）排序。 */
    public List<Member> page(UUID tenantId, long offset, int limit) {
        return memberships.page(tenantId, offset, limit).stream()
                .map(row -> new Member(
                        row.membershipId(), row.account(), row.displayName(), row.status(), parse(row.attributes())))
                .toList();
    }

    /** 租户内未删除的成员数。 */
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
