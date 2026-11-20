-- ============================================================================
-- 事项表：日程（schedule）与待办（todo）统一存储
--
-- 为什么不拆两张表：
--   两者都是「用户要做的一件事」——同一个列表、同一套完成/取消、同一套提醒、
--   同一张图表。拆开会让「今天有什么事」变成两次查询 + 两段排序合并，
--   而它们的差异只有三个可空时间字段，用 item_type 区分即可。
--
-- 时间字段的约定（一定要守住，否则前端画时间轴会错）：
--   - schedule：start_time 必填；end_time 可空（不知道几点结束）；due_time 恒为 NULL
--   - todo    ：start_time / end_time 恒为 NULL；due_time 可空（没有截止日期也要能建）
--
-- 状态只有三档，没有 failed：
--   待办没完成就是 0，过期没做完也不会变成失败——它只是「还没做」。
--   status=2（已取消）用于「不做了但想留痕」，与删除（deleted=1，不可见）不同。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `schedule_item`
(
    `id`             bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`        bigint       NOT NULL COMMENT '归属用户ID；所有查询都必须带这个条件',
    `item_type`      varchar(16)  NOT NULL COMMENT '事项类型：schedule-日程 todo-待办',
    `title`          varchar(200) NOT NULL COMMENT '标题',
    `content`        text COMMENT '详细内容 / 备注',
    `location`       varchar(255)          DEFAULT NULL COMMENT '地点，仅日程使用',
    `start_time`     datetime              DEFAULT NULL COMMENT '开始时间：日程必填，待办为 NULL',
    `end_time`       datetime              DEFAULT NULL COMMENT '结束时间，可空',
    `due_time`       datetime              DEFAULT NULL COMMENT '截止时间：待办使用，日程为 NULL',
    `all_day`        tinyint      NOT NULL DEFAULT 0 COMMENT '是否全天：0-否 1-是（仅日程有意义）',
    `priority`       tinyint      NOT NULL DEFAULT 1 COMMENT '优先级：0-低 1-中 2-高',
    `status`         tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0-未完成 1-已完成 2-已取消',
    `completed_time` datetime              DEFAULT NULL COMMENT '完成时间；未完成时为 NULL',
    `tags`           varchar(255)          DEFAULT NULL COMMENT '标签，逗号分隔，如「工作,重要」',
    `request_id`     varchar(64)           DEFAULT NULL COMMENT '幂等键：同一用户下相同值视为同一次创建，重试不会多建一条',
    `source`         varchar(16)  NOT NULL DEFAULT 'web' COMMENT '数据来源：web-前端手写 agent-智能体代操作',
    -- 重复系列：一次性事项这两列为 NULL。规则本体在 schedule_series，
    -- 这里只存归属与「第几次」，所以本表「一行一个具体时刻」的语义没变
    `series_id`      bigint                DEFAULT NULL COMMENT '所属重复系列ID，指向 schedule_series.id；一次性事项为 NULL',
    `series_index`   int                   DEFAULT NULL COMMENT '在系列里是第几次（从 1 开始）；一次性事项为 NULL',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    -- 「某天的日程」：按 user + start_time 做范围扫描，覆盖 agenda 与时间轴
    KEY `idx_user_start` (`user_id`, `start_time`),
    -- 「快到期 / 已过期的待办」：按 user + due_time 扫描
    KEY `idx_user_due` (`user_id`, `due_time`),
    -- 列表页按类型 + 状态过滤
    KEY `idx_user_type_status` (`user_id`, `item_type`, `status`),
    -- 所有查询都带 deleted=0，放在末尾让上面的索引先过滤掉大部分行
    KEY `idx_user_deleted` (`user_id`, `deleted`),
    -- 「把这个系列的实例全查出来 / 批量改」：等值匹配后量级很小（单系列上限 100 条）
    KEY `idx_user_series` (`user_id`, `series_id`),
    -- 创建幂等：MySQL 的唯一索引允许多个 NULL，所以不传 request_id 时不受影响
    UNIQUE KEY `uk_user_request_id` (`user_id`, `request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='事项表：日程与待办统一存储';
