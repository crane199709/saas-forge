-- V27 增加素材有效性判断时覆盖了 V25 的启用工作流 Fence；前向恢复失败关闭条件。
CREATE OR REPLACE FUNCTION list_accessible_membership_contexts(p_identity_id UUID)
RETURNS TABLE (
    membership_id UUID, tenant_id UUID, tenant_display_name VARCHAR(200),
    brand_display_name VARCHAR(200), brand_logo_url VARCHAR(2048), brand_favicon_url VARCHAR(2048),
    brand_primary_color CHAR(7), brand_accent_color CHAR(7))
LANGUAGE SQL STABLE SECURITY DEFINER SET search_path = pg_catalog, public
AS $$
    SELECT membership.id, tenant.id, tenant.display_name, brand.display_name, brand.logo_url,
           brand.favicon_url, brand.primary_color, brand.accent_color
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    LEFT JOIN public.tenant_brand_profiles AS brand ON brand.tenant_id = tenant.id
        AND EXISTS (SELECT 1 FROM public.tenant_brand_assets AS logo
                    WHERE logo.tenant_id = tenant.id AND logo.kind = 'LOGO' AND logo.status = 'ACTIVE'
                      AND brand.logo_url = '/brands/' || logo.id::TEXT)
        AND EXISTS (SELECT 1 FROM public.tenant_brand_assets AS favicon
                    WHERE favicon.tenant_id = tenant.id AND favicon.kind = 'FAVICON' AND favicon.status = 'ACTIVE'
                      AND brand.favicon_url = '/brands/' || favicon.id::TEXT)
    WHERE membership.identity_id = p_identity_id AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_lifecycle_workflows AS closure
          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_disable_workflows AS disabling
          WHERE disabling.tenant_id = membership.tenant_id AND disabling.membership_id = membership.id
            AND disabling.workflow_status IN ('REVOKING', 'RECOVERY_REQUIRED', 'RELEASE_PENDING'))
      AND NOT EXISTS (
          SELECT 1 FROM public.membership_enable_workflows AS enabling
          WHERE enabling.tenant_id = membership.tenant_id AND enabling.membership_id = membership.id
            AND enabling.workflow_status IN ('CONSUMING', 'COMPENSATION_PENDING', 'FENCE_PENDING'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id LIMIT 101
$$;
