package io.saas.forge.tenantaccess.application.brand;

import java.util.UUID;

public interface BrandProfileRepository {
    BrandProfileRevision read(UUID tenantId);

    /** 同一事务中锁定 Tenant，比较 revision 并复核素材状态、归属和类型。 */
    BrandProfileRevision save(UUID tenantId, long expectedRevision, BrandProfileChange change);
}
