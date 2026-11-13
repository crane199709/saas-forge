-- 字典历史与字典修改在同一事务写入，只保存完整租户覆盖集合，不保存 Manifest 的内置默认项。
CREATE TABLE dictionary_history (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    dictionary varchar(64) NOT NULL,
    version bigint NOT NULL CHECK (version > 0),
    snapshot jsonb NOT NULL,
    summary varchar(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE dictionary_history IS '租户字典覆盖设置的逐次变更历史与完整快照';
COMMENT ON COLUMN dictionary_history.id IS '主键，UUIDv7';
COMMENT ON COLUMN dictionary_history.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN dictionary_history.service IS '声明字典的服务';
COMMENT ON COLUMN dictionary_history.dictionary IS '字典编码';
COMMENT ON COLUMN dictionary_history.version IS '同一租户、服务与字典内递增的历史版本号';
COMMENT ON COLUMN dictionary_history.snapshot IS '变更后的完整租户覆盖集合，不含服务清单的内置默认项';
COMMENT ON COLUMN dictionary_history.summary IS '本次字典项变更摘要';
COMMENT ON COLUMN dictionary_history.created_at IS '变更发生时间';
COMMENT ON COLUMN dictionary_history.updated_at IS '最后更新时间';
COMMENT ON COLUMN dictionary_history.created_by IS '变更人 Identity ID，系统写入时为 NULL';
COMMENT ON COLUMN dictionary_history.updated_by IS '最后更新人 Identity ID，系统写入时为 NULL';
COMMENT ON COLUMN dictionary_history.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX dictionary_history_version_key
    ON dictionary_history (tenant_id, service, dictionary, version) WHERE NOT deleted;
ALTER TABLE dictionary_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE dictionary_history FORCE ROW LEVEL SECURITY;
CREATE POLICY dictionary_history_isolation ON dictionary_history
USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
