-- Platform Realm 的账号独立建表，不与 Identity 共用（ADR-0003）；Operator 不属于任何租户，因此没有 tenant_id 与 RLS。
CREATE TABLE operator (
    id uuid PRIMARY KEY,
    account varchar(120) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
