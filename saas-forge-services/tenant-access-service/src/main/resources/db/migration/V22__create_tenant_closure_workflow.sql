ALTER TABLE tenant_lifecycle_workflows DROP CONSTRAINT ck_tenant_lifecycle_action;
ALTER TABLE tenant_lifecycle_workflows ADD CONSTRAINT ck_tenant_lifecycle_action
    CHECK (lifecycle_action IN ('SUSPEND', 'RESUME', 'CLOSE'));
ALTER TABLE tenant_lifecycle_workflows DROP CONSTRAINT ck_tenant_lifecycle_action_requests;
ALTER TABLE tenant_lifecycle_workflows ADD CONSTRAINT ck_tenant_lifecycle_action_requests CHECK (
    (lifecycle_action IN ('SUSPEND', 'CLOSE') AND release_request_id IS NULL)
    OR (lifecycle_action = 'RESUME' AND release_request_id IS NOT NULL));

CREATE OR REPLACE FUNCTION claim_tenant_lifecycle_workflow(
    requested_workflow_id UUID, requested_claimant TEXT, requested_at TIMESTAMPTZ,
    requested_until TIMESTAMPTZ, requested_maximum_attempts INTEGER)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE target_tenant_id UUID;
BEGIN
    UPDATE public.tenant_lifecycle_workflows workflow
    SET workflow_status = CASE WHEN workflow.fence_established OR workflow.revocation_call_started
                               THEN 'RECOVERY_REQUIRED' ELSE 'RETRY_REQUIRED' END,
        recovery_exhausted_at = requested_at, lease_owner = NULL, lease_until = NULL,
        last_failure = 'RECOVERY_ATTEMPT_LIMIT_REACHED'
    WHERE workflow.workflow_id = requested_workflow_id
      AND workflow.lifecycle_action IN ('SUSPEND', 'CLOSE') AND workflow.workflow_status = 'PENDING'
      AND workflow.attempt_count >= requested_maximum_attempts
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at);

    UPDATE public.tenant_lifecycle_workflows workflow
    SET lease_owner = requested_claimant, lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1, fencing_token = workflow.fencing_token + 1
    WHERE workflow.workflow_id = requested_workflow_id AND workflow.workflow_status = 'PENDING'
      AND (workflow.lifecycle_action = 'RESUME' OR workflow.attempt_count < requested_maximum_attempts)
      AND workflow.next_attempt_at <= requested_at
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
    RETURNING workflow.tenant_id INTO target_tenant_id;

    IF target_tenant_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN requested_workflow_id;
END;
$$;

CREATE OR REPLACE FUNCTION claim_next_tenant_lifecycle_workflow(
    requested_claimant TEXT, requested_at TIMESTAMPTZ, requested_until TIMESTAMPTZ,
    requested_maximum_attempts INTEGER)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
DECLARE selected_workflow_id UUID; target_tenant_id UUID;
BEGIN
    UPDATE public.tenant_lifecycle_workflows workflow
    SET workflow_status = CASE WHEN workflow.fence_established OR workflow.revocation_call_started
                               THEN 'RECOVERY_REQUIRED' ELSE 'RETRY_REQUIRED' END,
        recovery_exhausted_at = requested_at, lease_owner = NULL, lease_until = NULL,
        last_failure = 'RECOVERY_ATTEMPT_LIMIT_REACHED'
    WHERE workflow.lifecycle_action IN ('SUSPEND', 'CLOSE') AND workflow.workflow_status = 'PENDING'
      AND workflow.attempt_count >= requested_maximum_attempts
      AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at);

    WITH candidate AS (
        SELECT workflow.workflow_id FROM public.tenant_lifecycle_workflows workflow
        WHERE workflow.workflow_status = 'PENDING'
          AND (workflow.lifecycle_action = 'RESUME' OR workflow.attempt_count < requested_maximum_attempts)
          AND workflow.next_attempt_at <= requested_at
          AND (workflow.lease_until IS NULL OR workflow.lease_until <= requested_at)
        ORDER BY workflow.next_attempt_at, workflow.created_at, workflow.workflow_id
        FOR UPDATE SKIP LOCKED LIMIT 1
    )
    UPDATE public.tenant_lifecycle_workflows workflow
    SET lease_owner = requested_claimant, lease_until = requested_until,
        attempt_count = workflow.attempt_count + 1, fencing_token = workflow.fencing_token + 1
    FROM candidate WHERE workflow.workflow_id = candidate.workflow_id
    RETURNING workflow.workflow_id, workflow.tenant_id INTO selected_workflow_id, target_tenant_id;

    IF selected_workflow_id IS NULL THEN RETURN NULL; END IF;
    PERFORM pg_catalog.set_config('app.tenant_id', target_tenant_id::TEXT, true);
    RETURN selected_workflow_id;
END;
$$;


CREATE OR REPLACE FUNCTION validate_membership(p_identity_id UUID, p_membership_id UUID)
RETURNS TABLE (tenant_id UUID)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT tenant.id
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    WHERE membership.id = p_membership_id
      AND membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_lifecycle_workflows AS closure
          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
$$;

CREATE OR REPLACE FUNCTION list_accessible_membership_contexts(p_identity_id UUID)
RETURNS TABLE (
    membership_id UUID,
    tenant_id UUID,
    tenant_display_name VARCHAR(200),
    brand_display_name VARCHAR(200),
    brand_logo_url VARCHAR(2048),
    brand_favicon_url VARCHAR(2048),
    brand_primary_color CHAR(7),
    brand_accent_color CHAR(7)
)
LANGUAGE SQL
STABLE
SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    SELECT membership.id,
           tenant.id,
           tenant.display_name,
           brand.display_name,
           brand.logo_url,
           brand.favicon_url,
           brand.primary_color,
           brand.accent_color
    FROM public.memberships AS membership
    JOIN public.tenants AS tenant ON tenant.id = membership.tenant_id
    LEFT JOIN public.tenant_brand_profiles AS brand ON brand.tenant_id = tenant.id
    WHERE membership.identity_id = p_identity_id
      AND membership.membership_status = 'ENABLED'
      AND tenant.tenant_status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM public.tenant_lifecycle_workflows AS closure
          WHERE closure.tenant_id = tenant.id AND closure.lifecycle_action = 'CLOSE'
            AND closure.workflow_status IN ('PENDING', 'RECOVERY_REQUIRED'))
      AND (tenant.expires_at IS NULL OR tenant.expires_at > statement_timestamp())
    ORDER BY tenant.display_name COLLATE "C", membership.id
    LIMIT 101
$$;
