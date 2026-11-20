-- ============================================================================
-- 提醒表：一行 = 一个提醒时刻
--
-- 为什么独立成表而不是在 schedule_item 上放一个 remind_time：
--   一个事项常常要提醒多次（提前一天准备、提前 30 分钟出发），单字段表达不了；
--   而且扫描要按「待发送 + 时刻已到」找行，独立表才能建出真正有用的索引，
--   不必每次扫全表再在应用里判断。
--
-- offset_minutes 存的只是「提前了多少分钟」，仅用于展示与重算；
-- 真正驱动扫描的是 remind_time（绝对时刻）——用户改了开始时间后，
-- 服务端会按 offset 重算 remind_time，而不是让扫描去算相对时间。
--
-- 状态机（只有扫描任务会改 status）：
--   0 待发送 → 1 已发送
--   0 待发送 → 3 发送失败（重试超过上限）
--   0 待发送 → 2 已取消（事项被完成 / 取消 / 删除，或用户改了提醒设置）
-- 没有任何路径会把 1 改回 0：重复提醒的代价比漏提醒更烦人。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `schedule_reminder`
(
    `id`             bigint      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint      NOT NULL COMMENT '归属用户ID（冗余自事项，扫描时直接用来填通知接收人）',
    `item_id`        bigint      NOT NULL COMMENT '关联 schedule_item.id',
    `remind_time`    datetime    NOT NULL COMMENT '实际触发时刻 = 事项时间 - offset_minutes',
    `offset_minutes` int         NOT NULL DEFAULT 0 COMMENT '提前分钟数：0-准时，1440-提前一天',
    `channel`        varchar(16) NOT NULL DEFAULT 'inbox' COMMENT '发送渠道：inbox-站内信（预留 sms/email）',
    `status`         tinyint     NOT NULL DEFAULT 0 COMMENT '状态：0-待发送 1-已发送 2-已取消 3-发送失败',
    `retry_count`    int         NOT NULL DEFAULT 0 COMMENT '投递失败重试次数',
    `sent_time`      datetime             DEFAULT NULL COMMENT '实际投递成功时间',
    `create_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    -- 扫描任务的唯一查询：status=0 AND remind_time <= NOW()，本索引直接命中
    KEY `idx_scan` (`status`, `remind_time`),
    -- 查看某事项的提醒、更新/取消时按 item_id 定位
    KEY `idx_item` (`item_id`),
    -- 「我未来几天的提醒」列表
    KEY `idx_user_time` (`user_id`, `remind_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='提醒表：一行一个提醒时刻';
