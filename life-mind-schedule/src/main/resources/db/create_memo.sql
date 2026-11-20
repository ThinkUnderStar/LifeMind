-- ============================================================================
-- 备忘录表
--
-- 为什么不复用 schedule_item：
--   备忘录没有「时间点」和「完成状态」这两个概念，塞进事项表只会多出三个恒为 NULL
--   的列、以及一个永远不该被点亮的 status。它更接近「便签」：内容为主、可置顶、
--   按更新时间排序。
--
-- title 允许为空：随手记一句话时不该被迫起标题，前端用 content 首行兜底展示。
-- pinned 用 tinyint 而不是排序字段：置顶是唯一需要人工干预的顺序，
-- 其余一律按 update_time 倒序，不需要维护 sort 值。
-- ============================================================================

CREATE TABLE IF NOT EXISTS `memo`
(
    `id`          bigint       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`     bigint       NOT NULL COMMENT '归属用户ID',
    `title`       varchar(200)          DEFAULT NULL COMMENT '标题，可为空（纯速记）',
    `content`     text         NOT NULL COMMENT '正文',
    `tags`        varchar(255)          DEFAULT NULL COMMENT '标签，逗号分隔',
    `pinned`      tinyint      NOT NULL DEFAULT 0 COMMENT '是否置顶：0-否 1-是',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除 1-已删除',
    PRIMARY KEY (`id`),
    -- 列表页：置顶在前、其余按更新时间倒序
    KEY `idx_user_pinned` (`user_id`, `pinned`, `update_time`),
    KEY `idx_user_deleted` (`user_id`, `deleted`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_0900_ai_ci COMMENT ='备忘录表';
