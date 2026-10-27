-- forge-sdk-mq 需要的表。服务把本文件内容复制到自己的 Flyway 迁移中执行，SDK 不自带迁移，
-- 以免与服务的迁移版本号交错；表结构变更时 SDK 会在发布说明中给出升级语句。
-- 两张表都不带 RLS：投递器与去重没有租户上下文，需要跨租户访问；tenant_id 只用于投递时还原消息头。

CREATE TABLE forge_outbox (
    id uuid PRIMARY KEY,
    tenant_id uuid,
    topic varchar(255) NOT NULL,
    tag varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    attempts integer NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now()
);
-- 投递器只扫描未发送的事件，部分索引让已发送的历史行不拖慢扫描
CREATE INDEX forge_outbox_due_idx ON forge_outbox (next_attempt_at) WHERE sent_at IS NULL;

CREATE TABLE forge_consumed_message (
    consumer_group varchar(255) NOT NULL,
    message_id varchar(128) NOT NULL,
    consumed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_group, message_id)
);
