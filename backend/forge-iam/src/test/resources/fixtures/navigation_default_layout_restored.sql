-- 恢复迁移写入的两条默认布局。
UPDATE navigation_default_layout SET deleted = false, updated_at = CURRENT_TIMESTAMP
WHERE id IN ('019a6e00-0000-7000-8000-000000000001', '019a6e00-0000-7000-8000-000000000002');
