-- 一致性测试数据：覆盖 NULL 列、空数组、含 NULL 元素的数组、ltree 前缀陷阱与数值精度差异
CREATE EXTENSION IF NOT EXISTS ltree;

CREATE TABLE abac_document (
    id              integer PRIMARY KEY,
    owner_id        uuid,
    department_path ltree,
    title           text,
    amount          numeric,
    archived        boolean,
    tags            text[]
);

INSERT INTO abac_document (id, owner_id, department_path, title, amount, archived, tags) VALUES
    (1, '019a0110-0000-7000-8000-00000000000a', 'root.sales',            'alpha',   100,    false, '{red,blue}'),
    (2, '019a0110-0000-7000-8000-00000000000b', 'root.sales.east',       'beta',    50.5,   true,  '{red}'),
    (3, '019a0110-0000-7000-8000-00000000000a', 'root.sales.east.team1', 'gamma',   200,    false, '{}'),
    (4, '019a0110-0000-7000-8000-00000000000c', 'root.rd',               'delta',   0,      NULL,  NULL),
    (5, NULL,                                   NULL,                    NULL,      NULL,   NULL,  '{blue,NULL}'),
    (6, '019a0110-0000-7000-8000-00000000000b', 'root.salesx',           'O''Brien', -10,   true,  '{NULL}'),
    (7, '019a0110-0000-7000-8000-00000000000c', 'root',                  'alpha',   100.00, false, '{green}'),
    (8, '019a0110-0000-7000-8000-00000000000a', 'root.rd.lab',           'epsilon', 1000,   false, '{red,green}');
