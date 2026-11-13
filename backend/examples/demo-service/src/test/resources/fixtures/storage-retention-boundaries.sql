-- 模拟历史/损坏登记：宽限已结束，但上传凭证仍有效，或登记的 key 已越出租户/服务边界。
SELECT set_config('app.tenant_id', '019d0110-0000-7000-8000-000000000099', false);
INSERT INTO forge_storage_object
    (id, tenant_id, object_key, purpose, filename, content_type, size, status, upload_expires_at, expired_at)
VALUES
    (CAST('019d0110-0000-7000-8000-000000000081' AS uuid),
     CAST('019d0110-0000-7000-8000-000000000099' AS uuid),
     '019d0110-0000-7000-8000-000000000098/demo-service/019d0110-0000-7000-8000-000000000081',
     'order-attachment', 'foreign.pdf', 'application/pdf', 1, 'EXPIRED',
     TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00'),
    (CAST('019d0110-0000-7000-8000-000000000082' AS uuid),
     CAST('019d0110-0000-7000-8000-000000000099' AS uuid),
     '019d0110-0000-7000-8000-000000000099/other-service/019d0110-0000-7000-8000-000000000082',
     'order-attachment', 'foreign-service.pdf', 'application/pdf', 1, 'EXPIRED',
     TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00', TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00'),
    (CAST('019d0110-0000-7000-8000-000000000083' AS uuid),
     CAST('019d0110-0000-7000-8000-000000000099' AS uuid),
     '019d0110-0000-7000-8000-000000000099/demo-service/019d0110-0000-7000-8000-000000000083',
     'order-attachment', 'active-upload.pdf', 'application/pdf', 1, 'EXPIRED',
     TIMESTAMP WITH TIME ZONE '2026-11-10 00:00:00+00', TIMESTAMP WITH TIME ZONE '2026-11-01 00:00:00+00');
SELECT set_config('app.tenant_id', '', false);
