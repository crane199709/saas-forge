-- 跨 Tenant 领取持久补偿，不依赖邀请仍待处理或 Tenant 仍可访问。
CREATE FUNCTION claim_next_invitation_compensation(p_now TIMESTAMPTZ)
RETURNS TABLE (invitation_id UUID, tenant_id UUID, identity_id UUID, idempotency_key UUID,
               consume_operation_id UUID, release_operation_id UUID, membership_id UUID, state TEXT)
LANGUAGE SQL VOLATILE SECURITY DEFINER
SET search_path = pg_catalog, public
AS $$
    WITH candidate AS (
        SELECT attempt.invitation_id
        FROM public.invitation_acceptance_attempts AS attempt
        WHERE attempt.state = 'COMPENSATING'
          AND attempt.updated_at <= p_now - INTERVAL '30 seconds'
        ORDER BY attempt.updated_at, attempt.invitation_id
        FOR UPDATE SKIP LOCKED LIMIT 1
    ), claimed AS (
        UPDATE public.invitation_acceptance_attempts AS attempt
        SET updated_at = p_now
        FROM candidate
        WHERE attempt.invitation_id = candidate.invitation_id AND attempt.state = 'COMPENSATING'
        RETURNING attempt.invitation_id, attempt.tenant_id, attempt.identity_id,
                  attempt.idempotency_key, attempt.consume_operation_id,
                  attempt.release_operation_id, attempt.membership_id, attempt.state
    )
    SELECT claimed.invitation_id, claimed.tenant_id, claimed.identity_id,
           claimed.idempotency_key, claimed.consume_operation_id,
           claimed.release_operation_id, claimed.membership_id, claimed.state
    FROM claimed
$$;
REVOKE ALL ON FUNCTION claim_next_invitation_compensation(TIMESTAMPTZ) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION claim_next_invitation_compensation(TIMESTAMPTZ) TO tenant_access_app;
