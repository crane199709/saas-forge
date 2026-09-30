-- 独立维护进程使用 project_migrator 执行；应用进程不持有该凭据。
-- 只回收已完成超过重放窗口的结果，不删除业务 Project 或未完成记录。
DELETE FROM project_write_results
WHERE completed_at <= clock_timestamp() - interval '24 hours';
