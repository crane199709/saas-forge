-- Notification 按 ADR-0022 使用非标准分区表。IAM 与既有表一致，由 SQL 显式限定租户（ADR-0001）。
-- 主记录先幂等占位，再固定收件人；重投时不会按变化后的权限增加收件人。
CREATE TABLE notification (
    id uuid NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id uuid NOT NULL,
    service varchar(255) NOT NULL,
    title varchar(200) NOT NULL,
    body varchar(10000) NOT NULL,
    app_code varchar(64),
    route varchar(2048),
    received_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id, occurred_at, tenant_id)
) PARTITION BY RANGE (occurred_at);
COMMENT ON TABLE notification IS '站内通知内容：收件人在创建时固定，按月分区保留至少180天';
COMMENT ON COLUMN notification.id IS '产出方生成的通知ID';
COMMENT ON COLUMN notification.occurred_at IS '业务发生时间及UTC月份分区键';
COMMENT ON COLUMN notification.tenant_id IS '所属租户ID';
COMMENT ON COLUMN notification.service IS '产出通知的业务服务';
COMMENT ON COLUMN notification.title IS '纯文本标题';
COMMENT ON COLUMN notification.body IS '纯文本正文';
COMMENT ON COLUMN notification.app_code IS '站内链接的Sub-App编码';
COMMENT ON COLUMN notification.route IS '站内链接的Sub-App路由';
COMMENT ON COLUMN notification.received_at IS 'IAM收到通知的时间';

CREATE TABLE notification_recipient (
    notification_id uuid NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    tenant_id uuid NOT NULL,
    membership_id uuid NOT NULL,
    read_at TIMESTAMP WITH TIME ZONE,
    -- 内容和收件人同事务写入，不建跨分区外键，避免外键阻止整月删除内容分区。
    PRIMARY KEY (notification_id, occurred_at, tenant_id, membership_id)
) PARTITION BY RANGE (occurred_at);
COMMENT ON TABLE notification_recipient IS '站内通知收件快照与已读状态，随通知按月清理';
COMMENT ON COLUMN notification_recipient.notification_id IS '通知ID';
COMMENT ON COLUMN notification_recipient.occurred_at IS '通知发生时间及UTC月份分区键';
COMMENT ON COLUMN notification_recipient.tenant_id IS '收件人所属租户ID';
COMMENT ON COLUMN notification_recipient.membership_id IS '创建时展开的收件Membership ID';
COMMENT ON COLUMN notification_recipient.read_at IS '首次已读时间，NULL表示未读';
CREATE INDEX notification_recipient_inbox_idx ON notification_recipient (tenant_id, membership_id, occurred_at DESC);

-- 运行账号不拥有DDL权限；固定search_path的迁移账号函数只维护通知自己的分区。
CREATE FUNCTION notification_ensure_partition(month_start date) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
DECLARE
    first_day date := CAST(date_trunc('month', month_start) AS date);
    table_name text;
BEGIN
    IF to_regclass('notification_' || to_char(first_day, 'YYYY_MM')) IS NOT NULL
        AND to_regclass('notification_recipient_' || to_char(first_day, 'YYYY_MM')) IS NOT NULL THEN
        RETURN;
    END IF;
    PERFORM pg_advisory_xact_lock(hashtext('notification_partition'));
    FOREACH table_name IN ARRAY ARRAY['notification', 'notification_recipient'] LOOP
        EXECUTE format('CREATE TABLE IF NOT EXISTS %I PARTITION OF %I FOR VALUES FROM (%L) TO (%L)',
            table_name || '_' || to_char(first_day, 'YYYY_MM'), table_name,
            CAST(first_day AS timestamp) AT TIME ZONE 'UTC',
            CAST(first_day + INTERVAL '1' MONTH AS timestamp) AT TIME ZONE 'UTC');
    END LOOP;
END;
$$;

-- 只删除整月均已满180天的分区，跨过截止日但尚有未满180天记录的月份继续保留。
CREATE FUNCTION notification_maintain_partitions(today date) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path FROM CURRENT AS $$
DECLARE
    first_retained_month date := CAST(date_trunc('month', today - INTERVAL '180' DAY) AS date);
    table_name text;
    child record;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('notification_partition'));
    PERFORM notification_ensure_partition(today);
    PERFORM notification_ensure_partition(CAST(today + INTERVAL '1' MONTH AS date));
    FOREACH table_name IN ARRAY ARRAY['notification_recipient', 'notification'] LOOP
        FOR child IN
            SELECT n.nspname AS schema_name, c.relname AS partition_name
            FROM pg_inherits i INNER JOIN pg_class c ON c.oid = i.inhrelid
                INNER JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE i.inhparent = to_regclass(table_name)
                AND c.relname ~ ('^' || table_name || '_[0-9]{4}_[0-9]{2}$')
        LOOP
            IF to_date(right(child.partition_name, 7), 'YYYY_MM') < first_retained_month THEN
                EXECUTE format('DROP TABLE %I.%I', child.schema_name, child.partition_name);
            END IF;
        END LOOP;
    END LOOP;
END;
$$;
SELECT notification_maintain_partitions(CAST(CURRENT_TIMESTAMP AT TIME ZONE 'UTC' AS date));
