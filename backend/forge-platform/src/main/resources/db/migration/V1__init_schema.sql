-- 初始迁移：证明 Flyway 以迁移账号（schema 所有者）执行的链路可用，业务表在后续工单中创建。
COMMENT ON SCHEMA platform IS 'forge-platform：租户、套餐、Provisioning 与 App Registry';
