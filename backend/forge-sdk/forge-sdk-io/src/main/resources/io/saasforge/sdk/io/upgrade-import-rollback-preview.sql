ALTER TABLE forge_import_backup ADD COLUMN superseded_by_task_id uuid;
COMMENT ON COLUMN forge_import_backup.superseded_by_task_id IS '在本次覆盖后实际再次覆盖同模型同记录的任务 ID，不按任务创建时间推断';
