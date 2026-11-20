-- ============================================================================
-- 重复系列表：一行 = 一条重复规则的模板
--
-- 为什么单独成表，而不是在 schedule_item 上挂一个 rrule 字段
-- ----------------------------------------------------------------------
-- 「模板」和「事项」是两种东西：模板没有具体时刻，但 schedule_item 的语义是
-- 「一行一个具体时刻」，status 只有 0/1/2 三档，没有哪一档能表达「这是模板，别当事项显示」。
-- 硬塞进去的后果是列表、概览、按日统计、逾期查询每一处都要额外加一条
-- 「AND series_id IS NULL」，漏改一处，用户就会看到「健身」在列表里出现 13 次。
--
-- 拆开之后 schedule_item 一个字段的语义都没变，也不用给 status 加新枚举值。
--
-- 为什么规则必须有终点（count_limit 或 until_date 至少填一个）
-- ----------------------------------------------------------------------
-- 允许「无限重复」就必须处理滚动补充：系列永远只能展开到未来 N 天，然后靠一个
-- 定时任务定期往后补。那个任务一旦挂了，用户在第 N+1 天就看不到日程了，
-- 而失败是静默的。要求必须有终点，创建时一次展开就结束，没有后台任务、没有补扫。
-- 代价是用户说「每天喝水」时 agent 得追问一句「先来多久」，交互成本远小于运维成本。
--
-- 为什么写时展开（物化），不是读时现算
-- ----------------------------------------------------------------------
-- 展开成实体行之后，提醒表、日视图、周视图、概览、按日统计全都不用改：
-- 扫描任务看到的还是一行一个绝对时刻的 remind_time。
-- 读时现算的话，五条读路径都要在 Java 里算一遍，提醒扫描还得按规则推算下一次，
-- 等于把整条提醒链路重写。
--
-- 代价（必须知道）：单次实例被单独改过时间后，它与规则的对应关系就断了；
-- 之后对这个系列做「重建」（改规则）会把这条实例按规则重算回来。
-- 想保留一次性的改动，就不要改系列规则。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `schedule_series`
(
    `id`               bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`          bigint       NOT NULL COMMENT '归属用户ID；所有查询都必须带这个条件',
    `item_type`        varchar(16)  NOT NULL COMMENT '展开成什么：schedule-日程 todo-待办',

    -- 模板内容：展开时复制进每个实例，之后再改系列不会回写已有实例（要回写见 Service 的 all/future 语义）
    `title`            varchar(200) NOT NULL COMMENT '标题',
    `content`          text COMMENT '详细内容 / 备注',
    `location`         varchar(255)          DEFAULT NULL COMMENT '地点，仅日程使用',
    `priority`         tinyint      NOT NULL DEFAULT 1 COMMENT '优先级：0-低 1-中 2-高',
    `all_day`          tinyint      NOT NULL DEFAULT 0 COMMENT '是否全天：0-否 1-是（仅日程有意义）',
    `tags`             varchar(255)          DEFAULT NULL COMMENT '标签，逗号分隔',

    -- 规则：只支持这三种频率。不做 RFC5545 全量（不涉及时区、夏令时、跳过节假日）
    `freq`             varchar(8)   NOT NULL COMMENT '频率：DAILY-每天 WEEKLY-每周 MONTHLY-每月',
    `interval_val`     int          NOT NULL DEFAULT 1 COMMENT '间隔：每 N 天/周/月，>=1',
    `by_day`           varchar(16)           DEFAULT NULL COMMENT '星期几：MO,WE,FR，逗号分隔；仅 WEEKLY 使用，空则取锚点当天',
    `until_date`       date                  DEFAULT NULL COMMENT '终止日期（含当天）；与 count_limit 至少填一个',
    `count_limit`      int                   DEFAULT NULL COMMENT '总次数上限（含第一次），1~100；与 until_date 至少填一个',

    -- 时间形状
    `base_time`        datetime     NOT NULL COMMENT '锚点时刻：日程取开始时间，待办取截止时间；第一次就从它开始',
    `duration_minutes` int                   DEFAULT NULL COMMENT '每次时长（分钟），仅日程；用于推算每条的 end_time，空则 end_time 为空',
    `remind_offsets`   varchar(64)           DEFAULT NULL COMMENT '提醒偏移，逗号分隔，如 "0,30,1440"；空表示不提醒',

    -- 状态：只有两档。没有「已删除」这一档——删除就是 deleted=1
    `status`           tinyint      NOT NULL DEFAULT 0 COMMENT '状态：0-生效 1-已结束（次数用完或过了终止日期）',
    `request_id`       varchar(64)           DEFAULT NULL COMMENT '幂等键：同一用户下相同值视为同一次创建，agent 重试不会建两套系列',
    `source`           varchar(16)  NOT NULL DEFAULT 'web' COMMENT '数据来源：web-前端手写 agent-智能体代操作',

    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`          tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    -- 「我的所有重复计划」：按用户 + 状态列
    KEY `idx_user_status` (`user_id`, `status`, `deleted`),
    -- 创建幂等：MySQL 唯一索引允许多个 NULL，不传 request_id 的老调用方式不受影响
    UNIQUE KEY `uk_user_request_id` (`user_id`, `request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='重复系列表：一行一条重复规则，实例展开在 schedule_item';
