-- 通用通知表：life_mind_message.notification（结构由库中现存表导出归档）
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
  `is_read` tinyint NOT NULL DEFAULT '0' COMMENT '是否已读：0-未读 1-已读',
  `read_time` datetime DEFAULT NULL COMMENT '已读时间',
  `extra` varchar(1000) DEFAULT NULL COMMENT '扩展数据，JSON格式字符串，存放业务附加参数',
  `deleted` tinyint NOT NULL DEFAULT '0' COMMENT '逻辑删除：0-未删除 1-已删除',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  KEY `idx_notification_user_read` (`user_id`,`is_read`),
  KEY `idx_notification_user_time` (`user_id`,`create_time`),
  KEY `idx_notification_biz` (`biz_type`,`biz_id`),
  KEY `idx_notification_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='通用通知表';