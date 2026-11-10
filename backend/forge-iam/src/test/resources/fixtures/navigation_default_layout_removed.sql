-- 默认布局记录不存在：逻辑删除迁移写入的两条默认布局，测试后由 navigation_default_layout_restored.sql 恢复。
UPDATE navigation_default_layout SET deleted = true, updated_at = CURRENT_TIMESTAMP WHERE NOT deleted;
