-- 升级后的现有套餐保留消息读取能力；后续可从 Feature Pack 中明确撤销。
UPDATE plan
SET permissions = permissions || CAST('{todo:read,notification:read,announcement:read}' AS text[]),
    updated_at = CURRENT_TIMESTAMP, updated_by = NULL
WHERE NOT deleted;
