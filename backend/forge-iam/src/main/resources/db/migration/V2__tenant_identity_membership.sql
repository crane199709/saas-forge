CREATE TABLE tenant (
    id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
CREATE TABLE identity (
    id uuid PRIMARY KEY,
    account varchar(120) NOT NULL UNIQUE CHECK (account NOT LIKE '%@%'),
    email varchar(254) NOT NULL UNIQUE,
    display_name varchar(120) NOT NULL,
    password_hash varchar(255) NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'active'
);
CREATE TABLE membership (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES identity(id),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    status varchar(20) NOT NULL DEFAULT 'active',
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (identity_id, tenant_id)
);
