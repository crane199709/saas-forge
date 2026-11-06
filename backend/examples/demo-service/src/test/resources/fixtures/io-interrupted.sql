SELECT set_config('app.tenant_id','019a0110-0000-7000-8000-000000000099',false);
INSERT INTO forge_io_task (id,tenant_id,type,status,membership_id,heartbeat_at)
VALUES (CAST('019a0110-0000-7000-8000-000000000098' AS uuid),CAST('019a0110-0000-7000-8000-000000000099' AS uuid),'EXPORT','RUNNING',CAST('019a0110-0000-7000-8000-000000000003' AS uuid),CURRENT_TIMESTAMP - INTERVAL '1' HOUR);
SELECT set_config('app.tenant_id','',false);
