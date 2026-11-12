-- AI 任务表：life_mind_agent.agent_task
-- 一行 = 一张前端任务卡。plan 变更时原地 UPDATE 本行（plan_id 不变），不新增任务、不物理删步骤。
-- MySQL 只存「供前端展示」的数据；任务执行结果不进本表。
CREATE TABLE IF NOT EXISTS `agent_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  `conversation_id` bigint NOT NULL COMMENT '会话ID，关联 conversation.id',
  `plan_id` varchar(64) NOT NULL COMMENT '计划ID，同一张任务卡原地更新，不随重规划变化',
  `goal` varchar(1000) NOT NULL COMMENT '任务目标，由 planner 从用户原话转写',
  `status` varchar(20) NOT NULL DEFAULT 'running' COMMENT '任务状态：running-执行中 done-已完成 failed-失败 interrupted-确认被拒/中断',
  `version` int NOT NULL DEFAULT '1' COMMENT '计划版本号，每次重规划 +1，前端按 version 幂等去重',
  `reason` varchar(500) DEFAULT NULL COMMENT '最近一次计划变更原因，如「发现 2 个冲突，补了 2 步」',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_plan_id` (`plan_id`),
  KEY `idx_task_conversation` (`conversation_id`,`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 任务表';
