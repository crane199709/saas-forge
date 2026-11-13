-- 分级与规则是全局表；租户覆盖显式限定 tenant_id，并启用强制 RLS 兜底。
CREATE TABLE masking_rule (
    id uuid PRIMARY KEY,
    code varchar(64) NOT NULL,
    name varchar(120) NOT NULL,
    strategy varchar(32) NOT NULL,
    keep_first integer,
    replacement varchar(256),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE masking_rule IS '平台固定策略库中的脱敏规则';
COMMENT ON COLUMN masking_rule.id IS '主键，UUIDv7';
COMMENT ON COLUMN masking_rule.code IS '规则编码';
COMMENT ON COLUMN masking_rule.name IS '规则名称';
COMMENT ON COLUMN masking_rule.strategy IS '固定策略名称';
COMMENT ON COLUMN masking_rule.keep_first IS 'keepFirst 策略保留的前缀码点数';
COMMENT ON COLUMN masking_rule.replacement IS 'replace 策略替换文本';
COMMENT ON COLUMN masking_rule.created_at IS '创建时间';
COMMENT ON COLUMN masking_rule.updated_at IS '最后更新时间';
COMMENT ON COLUMN masking_rule.created_by IS '创建人 ID，系统写入时为空';
COMMENT ON COLUMN masking_rule.updated_by IS '最后更新人 ID，系统写入时为空';
COMMENT ON COLUMN masking_rule.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX masking_rule_code_key ON masking_rule (code) WHERE NOT deleted;

CREATE TABLE data_grade (
    id uuid PRIMARY KEY,
    grade varchar(2) NOT NULL CHECK (grade IN ('L1', 'L2', 'L3', 'L4')),
    name varchar(20) NOT NULL,
    default_rule varchar(64) NOT NULL,
    export_allowed boolean NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false,
    CHECK (grade <> 'L4' OR NOT export_allowed)
);
COMMENT ON TABLE data_grade IS '固定四级数据分级及其平台默认规则';
COMMENT ON COLUMN data_grade.id IS '主键，UUIDv7';
COMMENT ON COLUMN data_grade.grade IS '数据分级编码 L1 至 L4';
COMMENT ON COLUMN data_grade.name IS '固定分级名称';
COMMENT ON COLUMN data_grade.default_rule IS '平台默认脱敏规则编码';
COMMENT ON COLUMN data_grade.export_allowed IS '是否允许导出，L4 固定禁止';
COMMENT ON COLUMN data_grade.created_at IS '创建时间';
COMMENT ON COLUMN data_grade.updated_at IS '最后更新时间';
COMMENT ON COLUMN data_grade.created_by IS '创建人 ID，系统写入时为空';
COMMENT ON COLUMN data_grade.updated_by IS '最后更新人 ID，系统写入时为空';
COMMENT ON COLUMN data_grade.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX data_grade_code_key ON data_grade (grade) WHERE NOT deleted;

INSERT INTO masking_rule (id, code, name, strategy, keep_first, replacement) VALUES
('019a0120-0000-7000-8000-000000000001', 'mask-middle', '保留首尾', 'mask_middle', NULL, NULL),
('019a0120-0000-7000-8000-000000000002', 'mask-tail4', '保留末四位', 'mask_tail4', NULL, NULL),
('019a0120-0000-7000-8000-000000000003', 'keep-first', '保留首位', 'keepFirst', 1, NULL),
('019a0120-0000-7000-8000-000000000004', 'replace', '全部隐藏', 'replace', NULL, '[已隐藏]'),
('019a0120-0000-7000-8000-000000000005', 'email', '邮箱', 'email', NULL, NULL),
('019a0120-0000-7000-8000-000000000006', 'phone', '手机号', 'phone', NULL, NULL),
('019a0120-0000-7000-8000-000000000007', 'idcard', '身份证', 'idcard', NULL, NULL),
('019a0120-0000-7000-8000-000000000008', 'bankcard', '银行卡', 'bankcard', NULL, NULL),
('019a0120-0000-7000-8000-000000000009', 'name', '姓名', 'name', NULL, NULL);
INSERT INTO data_grade (id, grade, name, default_rule, export_allowed) VALUES
('019a0120-0000-7000-8000-000000000011', 'L1', '公开', 'mask-middle', true),
('019a0120-0000-7000-8000-000000000012', 'L2', '内部', 'mask-middle', true),
('019a0120-0000-7000-8000-000000000013', 'L3', '敏感', 'replace', true),
('019a0120-0000-7000-8000-000000000014', 'L4', '极敏感', 'replace', false);

CREATE TABLE field_masking_override (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    service varchar(64) NOT NULL,
    path varchar(1024) NOT NULL,
    rule_code varchar(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by uuid,
    updated_by uuid,
    deleted boolean NOT NULL DEFAULT false
);
COMMENT ON TABLE field_masking_override IS '租户为清单敏感字段选择的脱敏规则，恢复默认使用逻辑删除';
COMMENT ON COLUMN field_masking_override.id IS '主键，UUIDv7';
COMMENT ON COLUMN field_masking_override.tenant_id IS '所属租户 ID';
COMMENT ON COLUMN field_masking_override.service IS '声明字段的服务';
COMMENT ON COLUMN field_masking_override.path IS '服务内敏感字段路径';
COMMENT ON COLUMN field_masking_override.rule_code IS '租户选择的脱敏规则编码';
COMMENT ON COLUMN field_masking_override.created_at IS '创建时间';
COMMENT ON COLUMN field_masking_override.updated_at IS '最后更新时间';
COMMENT ON COLUMN field_masking_override.created_by IS '创建人 Identity ID';
COMMENT ON COLUMN field_masking_override.updated_by IS '最后更新人 Identity ID';
COMMENT ON COLUMN field_masking_override.deleted IS '逻辑删除标记';
CREATE UNIQUE INDEX field_masking_override_key ON field_masking_override (tenant_id, service, path) WHERE NOT deleted;
ALTER TABLE field_masking_override ENABLE ROW LEVEL SECURITY;
ALTER TABLE field_masking_override FORCE ROW LEVEL SECURITY;
CREATE POLICY field_masking_override_isolation ON field_masking_override
USING (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid))
WITH CHECK (tenant_id = CAST(NULLIF(current_setting('app.tenant_id', true), '') AS uuid));
