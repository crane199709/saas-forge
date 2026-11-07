CREATE TABLE todo (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL,
    service varchar(255) NOT NULL,
    business_key varchar(255) NOT NULL,
    opened boolean NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    occurred_at_nanos integer NOT NULL CHECK (occurred_at_nanos >= 0 AND occurred_at_nanos < 1000000000),
    title varchar(200),
    body text,
    permission_code varchar(255),
    app_code varchar(64),
    route varchar(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE todo IS '业务服务维护的待办状态索引，关闭先到时也保留状态以抵御旧事件';
COMMENT ON COLUMN todo.id IS '待办 ID，UUIDv7';
COMMENT ON COLUMN todo.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN todo.service IS '来源业务服务';
COMMENT ON COLUMN todo.business_key IS '来源服务内唯一的业务键';
COMMENT ON COLUMN todo.opened IS '是否开启，关闭时对全部处理人隐藏';
COMMENT ON COLUMN todo.occurred_at IS '业务发生时间的整秒部分，与纳秒部分共同保留 Instant 精度';
COMMENT ON COLUMN todo.occurred_at_nanos IS '业务发生时间的纳秒部分，防止时间戳精度截断改变严格先后关系';
COMMENT ON COLUMN todo.title IS '纯文本标题，关闭先到时为空';
COMMENT ON COLUMN todo.body IS '纯文本正文，关闭先到时为空';
COMMENT ON COLUMN todo.permission_code IS '读取时判断的目标权限码，成员目标时为空';
COMMENT ON COLUMN todo.app_code IS '链接指向的 Sub-App 编码，超出当前套餐时隐藏';
COMMENT ON COLUMN todo.route IS 'Sub-App 内部路由';
COMMENT ON COLUMN todo.created_at IS '创建时间';
COMMENT ON COLUMN todo.updated_at IS '最后更新时间';
COMMENT ON COLUMN todo.created_by IS '创建人 Identity ID，系统事件为空';
COMMENT ON COLUMN todo.updated_by IS '最后更新人 Identity ID，系统事件为空';
COMMENT ON COLUMN todo.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX todo_business_key ON todo (tenant_id, service, business_key) WHERE NOT deleted;
CREATE INDEX todo_opened_idx ON todo (tenant_id, occurred_at DESC, occurred_at_nanos DESC, id) WHERE opened AND NOT deleted;
ALTER TABLE todo ENABLE ROW LEVEL SECURITY;
ALTER TABLE todo FORCE ROW LEVEL SECURITY;
CREATE POLICY todo_tenant_isolation ON todo
USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));

CREATE TABLE todo_membership (
    tenant_id uuid NOT NULL,
    todo_id uuid NOT NULL REFERENCES todo(id),
    membership_id uuid NOT NULL,
    PRIMARY KEY (todo_id, membership_id)
);
COMMENT ON TABLE todo_membership IS '待办明确指定的处理人集合，随更晚开启事件整体替换';
COMMENT ON COLUMN todo_membership.tenant_id IS '所属租户 ID，用于行级隔离';
COMMENT ON COLUMN todo_membership.todo_id IS '待办 ID';
COMMENT ON COLUMN todo_membership.membership_id IS '处理人的 Membership ID，仅包含所属租户的成员';
ALTER TABLE todo_membership ENABLE ROW LEVEL SECURITY;
ALTER TABLE todo_membership FORCE ROW LEVEL SECURITY;
CREATE POLICY todo_membership_tenant_isolation ON todo_membership
USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
