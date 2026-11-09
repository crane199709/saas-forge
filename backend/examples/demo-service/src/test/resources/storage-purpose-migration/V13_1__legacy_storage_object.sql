-- 在用途列加入前保留一条真实旧格式登记，验收回填后仍保留且不能冒用普通用途下载。
SELECT set_config('app.tenant_id', '019b0110-0000-7000-8000-000000000099', false);
INSERT INTO forge_storage_object (id, tenant_id, object_key, filename, content_type, size, status, upload_expires_at, stored_at)
VALUES (CAST('019b0110-0000-7000-8000-000000000098' AS uuid),
    CAST('019b0110-0000-7000-8000-000000000099' AS uuid),
    '019b0110-0000-7000-8000-000000000099/demo-service/019b0110-0000-7000-8000-000000000098',
    'legacy.pdf', 'application/pdf', 4, 'STORED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);
SELECT set_config('app.tenant_id', '', false);
