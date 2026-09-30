-- 稳定结果仅包括成功和业务 4xx；协议校验 400 由入口拒绝，不进入本表。
ALTER TABLE project_write_results DROP CONSTRAINT project_write_results_check;
ALTER TABLE project_write_results ADD CONSTRAINT project_write_results_complete_result CHECK (
    (status IS NULL AND body IS NULL AND completed_at IS NULL)
    OR ((status BETWEEN 200 AND 299 OR status BETWEEN 400 AND 499)
        AND body IS NOT NULL AND completed_at IS NOT NULL)
);
