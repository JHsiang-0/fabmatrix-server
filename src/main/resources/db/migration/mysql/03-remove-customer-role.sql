-- CUSTOMER 已从本地农场角色模型中移除。
-- 该脚本可用于已有数据库，也会在新 Docker 数据卷初始化时执行。

UPDATE farm_user
SET role = 'OPERATOR'
WHERE role = 'CUSTOMER';

ALTER TABLE farm_user
    MODIFY COLUMN role VARCHAR(20) NOT NULL DEFAULT 'OPERATOR'
        COMMENT '角色权限：ADMIN, OPERATOR';
