-- 通用通知表：life_mind_message.notification（结构由库中现存表导出归档）
--
-- event_id 的唯一索引是消费端幂等的最后一道防线
-- --------------------------------------------
-- RocketMQ 只承诺「至少投递一次」，同一条消息可能被投递两次以上：
--   * 消费成功但 ACK 丢失，Broker 认为没消费，重投；
--   * 消费抛异常，按重试策略重投（累计 16 次后进死信）；
--   * 生产端投递超时后自行重发。
-- 生产方为每次业务事件生成一个稳定的 eventId（如 schedule_reminder_123），
-- 消费端先按它查一次，落库时再被唯一索引兜住并发窗口。
--
-- 为什么不直接用 (biz_type, biz_id) 做唯一键
-- ------------------------------------------
-- 一个事项可以有多个提醒时刻（提前一天、提前 30 分钟），这些通知的
-- biz_id 都是同一个 item.id，用业务键去重会把正常的第二条提醒也挡掉。
-- 幂等粒度必须是「哪一次提醒」（schedule_reminder.id），不是「哪个事项」。
--
-- 注意：本表有 deleted 逻辑删除标记，而 MySQL 8 不支持部分索引。
-- 目前没有任何接口会删除通知；若将来要支持删除，必须先想清楚
-- 「删除后同一 eventId 重投怎么办」——否则会撞唯一键而无法插入。

CREATE TABLE `notification` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '通知ID',
  `user_id` bigint NOT NULL COMMENT '接收人用户ID',
  `type` varchar(50) NOT NULL COMMENT '通知类型：system-系统通知 like-点赞 comment-评论 reply-回复 follow-关注 feedback-反馈等，字符串编码便于业务扩展',
  `title` varchar(100) NOT NULL COMMENT '通知标题',
  `content` varchar(500) NOT NULL COMMENT '通知内容',
  `sender_id` bigint DEFAULT NULL COMMENT '发送者用户ID，系统通知为NULL',
  `biz_type` varchar(50) DEFAULT NULL COMMENT '关联业务类型，如 post/comment/feedback/task 等，可空',
  `biz_id` varchar(64) DEFAULT NULL COMMENT '关联业务ID，字符串形式以兼容各类业务主键',
  `jump_url` varchar(255) DEFAULT NULL COMMENT '点击通知后的跳转地址',
  `level` tinyint NOT NULL DEFAULT '0' COMMENT '通知级别：0-普通 1-重要 2-紧急',
  `need_popup` tinyint NOT NULL DEFAULT '0' COMMENT '是否需要弹窗提醒：0-否 1-是；只决定网页内是否打断用户，不涉及短信/邮件等外部渠道',
  `event_id` varchar(64) DEFAULT NULL COMMENT '事件幂等键：生产方保证同一次业务事件取值相同，用于抵消 MQ 至少一次投递带来的重复；手工/系统直插的通知可留空',
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读：0-未读 1-已读',
  `read_time` datetime DEFAULT NULL COMMENT '已读时间',
  `extra` varchar(1000) DEFAULT NULL COMMENT '扩展数据，JSON格式字符串，存放业务附加参数',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_notification_event` (`event_id`),
  KEY `idx_notification_user_read` (`user_id`,`is_read`),
  KEY `idx_notification_user_time` (`user_id`,`create_time`),
  KEY `idx_notification_biz` (`biz_type`,`biz_id`),
  KEY `idx_notification_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用通知表';