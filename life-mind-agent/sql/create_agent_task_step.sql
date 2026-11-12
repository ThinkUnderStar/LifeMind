-- AI 任务步骤表：life_mind_agent.agent_task_step
-- 一行 = 一个步骤，状态变更即落库（不等收尾同步）。展示顺序按 id 升序：新追加的步骤只能排在末尾，不做插队。
-- 不含 result 列：任务执行结果不展示、不进 MySQL，只在运行时 State 里活到 responder 消费完。
CREATE TABLE IF NOT EXISTS `agent_task_step` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '步骤行ID',
  `task_id` bigint NOT NULL COMMENT '所属任务ID，关联 agent_task.id',
  `step_id` varchar(64) NOT NULL COMMENT '计划内步骤编号（planner 生成，作废后追加新编号，不复用）',
  `goal` varchar(1000) NOT NULL COMMENT '本步目标',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT '步骤状态：pending-待执行 running-执行中 done-已完成 skipped-已跳过 failed-失败',
  `outcome` varchar(500) DEFAULT NULL COMMENT '状态说明 / 失败或跳过原因。不是执行结果，不存工具返回内容',
  `added_in` int NOT NULL DEFAULT '1' COMMENT '加入时的计划版本号，前端据此标记「新增步骤」',
  `changed_in` int DEFAULT NULL COMMENT '最后一次改动所在的计划版本号，NULL 表示从未改动',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_task_step` (`task_id`,`step_id`),
  KEY `idx_step_task_status` (`task_id`,`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 任务步骤表';
