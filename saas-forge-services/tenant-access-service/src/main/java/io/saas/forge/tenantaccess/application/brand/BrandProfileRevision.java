package io.saas.forge.tenantaccess.application.brand;

/** revision 0 表示该 Tenant 尚无 Profile；旧记录从 revision 1 开始。 */
public record BrandProfileRevision(long revision, BrandProfileChange profile) {}
