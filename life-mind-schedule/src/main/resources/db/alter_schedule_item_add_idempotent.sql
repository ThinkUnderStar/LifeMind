-- ============================================================================
-- 给已存在的 schedule_item 补两列：幂等键 request_id 与来源 source
--
-- 背景：接口要能被 agent 调用，而 agent 会重试（网络超时、图重跑、用户说「再来一次」）。
-- 没有幂等键时「明天九点开会」会被建两条，用户会认为服务坏了。
-- source 则是为了事后能分清哪条是 agent 写的、哪条是用户手写的。
--
-- 执行顺序：先跑本脚本，再启动新版本服务（旧版本不认识这两列，不影响运行）。
-- ============================================================================

ALTER TABLE `schedule_item`
    ADD COLUMN `request_id` varchar(64) DEFAULT NULL
        COMMENT '幂等键：同一用户下相同值视为同一次创建，重试不会多建一条' AFTER `tags`,
    ADD COLUMN `source` varchar(16) NOT NULL DEFAULT 'web'
        COMMENT '数据来源：web-前端手写 agent-智能体代操作' AFTER `request_id`,
    -- MySQL 的唯一索引允许多个 NULL，因此不传 request_id 的老调用方式不受影响
    ADD UNIQUE KEY `uk_user_request_id` (`user_id`, `request_id`);
