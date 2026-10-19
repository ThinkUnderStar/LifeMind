-- 登录审计表：记录每次登录，以及账号的创建/注销时间。
-- 设计要点（按业务要求）：
--   1. 不建外键、不加关联索引，即使账号注销，记录也仍保留（约定保留半年后由定时任务清理，此处仅建表）。
--   2. 用 type 字段区分审计类型，事件的业务时间统一落在 create_time。
--   3. 不做逻辑删除（deleted 字段），避免误删审计记录；清理走物理删除策略。
CREATE TABLE `login_audit` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     BIGINT       NOT NULL COMMENT '用户ID（不建外键，账号注销后记录仍保留半年）',
    `type`        TINYINT      NOT NULL COMMENT '审计类型：1-登录 2-创建账号 3-注销账号',
    `ip`          VARCHAR(64)  NULL COMMENT '来源IP',
    `region`      VARCHAR(255) NULL COMMENT '来源地区（如：中国-广东-深圳）',
    `user_agent`  VARCHAR(500) NULL COMMENT 'User-Agent，客户端指纹/设备信息',
    `device_type` TINYINT      NULL COMMENT '设备类型：1-PC 2-移动端 3-其他',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件创建时间（登录/创建/注销的发生时间）',
    `update_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_general_ci COMMENT = '登录审计表';