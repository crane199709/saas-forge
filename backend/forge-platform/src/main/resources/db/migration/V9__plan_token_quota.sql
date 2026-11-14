ALTER TABLE plan ADD COLUMN token_quota BIGINT CHECK (token_quota > 0);
COMMENT ON COLUMN plan.token_quota IS '每租户每月 Token 总量上限，NULL 表示不限';
