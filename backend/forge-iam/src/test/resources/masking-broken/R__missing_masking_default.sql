-- 模拟升级前已损坏的分级引用；不经业务接口构造非法配置。
UPDATE data_grade SET default_rule = 'missing-rule', updated_at = CURRENT_TIMESTAMP WHERE grade = 'L2' AND NOT deleted;
