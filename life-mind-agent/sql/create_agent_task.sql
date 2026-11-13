-- AI 任务表：life_mind_agent.agent_task
-- 一行 = 一个任务（任务就是步骤）。一个会话只有几条任务行，按 id 升序展示。
-- 归属只认 conversation_id：判断哪些任务属于同一个对话，只看这一列；没有计划ID、步骤编号、版本号之类的分组键。
-- 改任务 = 原地改那一行（status 改回 pending「开始」、重写 goal）；追加任务 = 插一条 status=pending 的新行。
-- 不做版本、不做变更记录、不做历史快照：表里只有这个会话当前的任务清单。
-- MySQL 存任务清单 + 每条任务的执行结果，供前端展示与断线重连；不存中间流水与逐 token 输出。
CREATE TABLE IF NOT EXISTS `agent_task` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '任务ID，主键；展示顺序按 id 升序',
  `conversation_id` bigint NOT NULL COMMENT '会话ID，关联 conversation.id；判断同一对话的任务只靠这一列',
  `goal` varchar(1000) NOT NULL COMMENT '任务目标，由 planner 从用户原话转写',
  `status` varchar(20) NOT NULL DEFAULT 'pending' COMMENT '任务状态：pending-开始/待执行 running-执行中 done-已完成 failed-失败或中断 retrying-重试中（原样再跑一次，不改 goal）',
  `result` text COMMENT '本任务的执行结果（工具产出 / AI 结论）；未执行完为 NULL。改任务或重试重跑时清空',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  PRIMARY KEY (`id`),
  KEY `idx_conversation` (`conversation_id`,`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='AI 任务表（一行一任务，只按会话归属）';
